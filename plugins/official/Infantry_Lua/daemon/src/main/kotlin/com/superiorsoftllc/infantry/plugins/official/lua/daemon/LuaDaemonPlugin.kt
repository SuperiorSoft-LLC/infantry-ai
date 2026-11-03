package com.superiorsoftllc.infantry.plugins.official.lua.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.plugins.official.scripting.daemon.ScriptEngineWire

/** SCRIPT-2: the LuaJ engine plugin; contributes engine id `lua` to Infantry_Scripting's extension point (`scripting.engine/lua`). */
class LuaDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        ScriptEngineWire.publish(api.services, LuaScriptEngine())
    }
}
