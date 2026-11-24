package com.harveybase.anytab.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * 项目级开关：和 Copilot 一样，可以只给某些项目关掉补全。
 * 存在 workspace 文件里（不进版本库，也不跨机器同步）。
 */
@Service(Service.Level.PROJECT)
@State(name = "AnyTabProjectState", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class AnyTabProjectState : PersistentStateComponent<AnyTabProjectState.State> {

    class State(var enabled: Boolean = true)

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun isEnabled(): Boolean = state.enabled

    fun setEnabled(enabled: Boolean) {
        if (state.enabled != enabled) {
            state.enabled = enabled
            AnyTabSettings.getInstance().fireChanged()
        }
    }

    fun toggle() = setEnabled(!state.enabled)

    companion object {
        fun getInstance(project: Project): AnyTabProjectState = project.service()
    }
}
