package com.morpheusdata.hvmupdater

class Util {
    static int asInt(Object v, int dflt) {
        String t = v?.toString()?.trim()
        return (t && t.isInteger()) ? (t as int) : dflt
    }

    static boolean asBool(Object v) {
        String t = v?.toString()?.trim()?.toLowerCase()
        return t in ['on', 'true', '1', 'yes']
    }
}
