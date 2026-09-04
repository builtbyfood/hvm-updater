package com.morpheusdata.hvmupdater

import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
import com.morpheusdata.views.HandlebarsRenderer
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

/**
 * HVM Updater plugin.
 *
 *  - Polls My HPE Software Center for releases the account can download.
 *  - Downloads + SHA-512-verifies packages into /var/opt/morpheus/hvm-updater/downloads (default; ~/$HOME expanded, /tmp warned).
 *  - Launches a detached upgrade runner (systemd transient unit) over SSH and shows
 *    its progress on /plugin/hvmUpdater, including after the UI restarts.
 */
@Slf4j
class HvmUpdaterPlugin extends Plugin {

    { this.renderer = new HandlebarsRenderer() }

    /** Root-written runner files (state, log, backups). Created by the launch via sudo. */
    static final String WORK_DIR = UpgradeService.WORK_DIR
    /** Plugin-written files (catalog cache, config, downloads). Lives where the UI user already has rights. */
    static final String PLUGIN_DIR = '/var/opt/morpheus/morpheus-ui/hvm-updater'

    Catalog catalog
    TaskAgent agent
    UpgradeService upgrades
    CredentialResolver creds
    TokenManager tokens
    HvmUpdaterController controller
    FleetService fleet

    @Override String getCode() { 'hvmUpdater' }
    @Override String getName() { 'HVM Updater' }

    @Override
    void initialize() {
        setName('HVM Updater')
        setDescription('Track, download and apply HPE Morpheus / VM Essentials releases from My HPE Software Center, with a resumable appliance upgrade runner.')
        setAuthor('Travis DeLuca')

        Closure<Map> cfg = { loadSettings() }
        Map s = cfg()
        creds     = new CredentialResolver(morpheus, cfg)
        agent     = new TaskAgent(morpheus, cfg, { config() })
        catalog   = new Catalog(cfg, PLUGIN_DIR)
        upgrades  = new UpgradeService(cfg, agent, creds)
        upgrades.configProvider = { config() }
        tokens    = new TokenManager(cfg, creds, this.&tokenShell)
        catalog.tokens = tokens

        fleet = new FleetService({ -> api() }, new File(PLUGIN_DIR))
        fleet.applianceServerId = { -> String v = config().applianceServerId?.toString(); (v && v.isLong()) ? (v as Long) : null }
        controller = new HvmUpdaterController(this, morpheus)
        this.controllers.add(controller)   // controllers are NOT providers — registerProvider(controller) only produced an UNKNOWN row in plugin settings

        catalog.start(Util.asInt(s.refreshMinutes, 360))
        log.info("HVM Updater initialized; page at /plugin/hvmUpdater")
    }

    @Override
    void onDestroy() {
        try { catalog?.stop() } catch (Exception ignored) {}
        try { agent?.shutdown() } catch (Exception ignored) {}
    }

    Boolean hasCustomRenderer() { true }

    @Override
    List<OptionType> getSettings() {
        int i = 0
        [
            opt('HPE token (x-authtoken)', 'hpeToken', OptionType.InputType.PASSWORD, null, i++,
                'Optional emergency fallback. Normally you just click "Sign in to HPE" on the plugin page; the password is never stored and the token is kept only in memory.'),
            opt('Products (comma-separated productNumbers, blank = all)', 'products', OptionType.InputType.TEXT, '', i++,
                'e.g. HPE_MORPHEUS_SW_CMT_ED_6,HPE_VME_EVAL'),
            opt('Catalog refresh interval (minutes)', 'refreshMinutes', OptionType.InputType.NUMBER, '360', i++, null),
            opt('Download directory', 'downloadDir', OptionType.InputType.TEXT, TaskAgent.DEFAULT_DOWNLOAD_DIR, i++,
                'Absolute path ON THE APPLIANCE to download packages to (curled directly there). Blank = ' + TaskAgent.DEFAULT_DOWNLOAD_DIR +
                '. ~ and $HOME are expanded to the command user\'s home. Avoid /tmp (tmpfs: lost on reboot, uses RAM). A remote mount (NFS/CIFS) works but installs slower by roughly the file size.'),

            opt('SSH host', 'sshHost', OptionType.InputType.TEXT, '127.0.0.1', i++, 'The appliance to upgrade. 127.0.0.1 = this appliance.'),
            opt('SSH port', 'sshPort', OptionType.InputType.NUMBER, '22', i++, null),
            opt('SSH credential (Trust ID or name)', 'sshCredential', OptionType.InputType.TEXT, '', i++,
                'Infrastructure → Trust → Credentials: a username/password (or username/key) credential for a sudo-capable account on the appliance. ' +
                'Use the numeric ID (hover the edit pencil to see it in the URL) so renames don\'t break it. Or a Cypher key like secret/hvm-updater-ssh holding user:password. The password is piped to sudo -S.'),
            opt('SSH user (fallback if no credential)', 'sshUser', OptionType.InputType.TEXT, '', i++, null),
            opt('SSH private key path (fallback)', 'sshKeyPath', OptionType.InputType.TEXT, '', i++, 'Only used when no Trust credential is set.'),

            opt('Backup directory', 'backupDir', OptionType.InputType.TEXT, "${WORK_DIR}/backups", i++, null),
            opt('Backups to keep', 'backupKeep', OptionType.InputType.NUMBER, '3', i++, null),
            opt('Wait for UI (minutes)', 'waitMinutes', OptionType.InputType.NUMBER, '25', i++, 'How long the runner waits for /api/ping after reconfigure.'),

            opt('Include /var/opt/morpheus/morpheus-ui in runner backup', 'backupUiDir', OptionType.InputType.CHECKBOX, 'off', i++,
                'Tar of the shared-storage dir (virtual images etc.). Can be large.'),

            opt('Appliance URL', 'applianceUrl', OptionType.InputType.TEXT, 'https://127.0.0.1', i++, 'Used for /api/ping and for running the pre-upgrade Morpheus backup.'),
            opt('Morpheus API token', 'apiToken', OptionType.InputType.PASSWORD, null, i++,
                'Used to list servers/backups and execute the selected backup before an upgrade (Administration → API tokens or user settings).'),
            opt('Backup wait (minutes)', 'backupWaitMinutes', OptionType.InputType.NUMBER, '90', i++,
                'How long to wait for the Morpheus backup to finish before giving up.'),

            opt('Progress webhook URL (optional)', 'webhookUrl', OptionType.InputType.TEXT, '', i++,
                'The runner POSTs the status JSON to this URL at every step — survives the UI outage. Any endpoint that accepts a JSON POST.'),
            opt('Mirror status file to path (optional)', 'mirrorPath', OptionType.InputType.TEXT, '', i++,
                'The runner also writes upgrade status + log to this path (e.g. an NFS/CIFS mount on the appliance) so another box can tail it while the Manager is down. A directory or a .json file path.'),
            opt('Send Morpheus notifications (start/finish only)', 'notifyEnabled', OptionType.InputType.CHECKBOX, 'off', i++,
                'Bookend notifications via the API — the API is down during reconfigure, so only started/completed/failed are sent.'),
        ]
    }

    private static OptionType opt(String label, String field, OptionType.InputType type, String dflt, int order, String help) {
        new OptionType(name: label, code: "hvmUpdater.${field}", fieldName: field, fieldLabel: label,
                       inputType: type, defaultValue: dflt, displayOrder: order, helpText: help)
    }

    MorpheusApi api() {
        Map s = loadSettings()
        new MorpheusApi(s.applianceUrl as String, s.apiToken as String)
    }

    /** Page-level config (appliance VM + backup selection), kept next to the catalog cache. */
    private File configFile() { new File(PLUGIN_DIR, 'config.json') }

    Map config() {
        try { return configFile().exists() ? (new JsonSlurper().parseText(configFile().getText('UTF-8')) as Map) : [:] }
        catch (Exception e) { return [:] }
    }

    Map saveConfig(Map c) {
        try {
            configFile().parentFile.mkdirs()
            configFile().setText(groovy.json.JsonOutput.toJson(c), 'UTF-8')
            return [saved: true]
        } catch (Exception e) {
            return [error: "cannot write ${configFile()}: ${e.message}"]
        }
    }

    /** TokenManager's shell fallback is unused now (HPE login is HTTP-replay only). */
    Map tokenShell(Map op) { [error: 'shell fallback not available'] }

    Map loadSettings() {
        try {
            String json = morpheus.getSettings(this).blockingGet()
            if (json) return new JsonSlurper().parseText(json) as Map
        } catch (Exception e) {
            log.debug("settings unavailable, using defaults: ${e.message}")
        }
        return [:]
    }
}
