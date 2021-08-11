package com.segment.analytics.kotlin.core.platform

import com.segment.analytics.kotlin.core.AliasEvent
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.GroupEvent
import com.segment.analytics.kotlin.core.IdentifyEvent
import com.segment.analytics.kotlin.core.ScreenEvent
import com.segment.analytics.kotlin.core.TrackEvent

// Platform abstraction for managing plugins' execution (of a specific type)
// All operations are thread safe via the `synchronized` function
internal class Mediator(internal val plugins: MutableList<Plugin>) {

    fun add(plugin: Plugin) = synchronized(plugins) {
        plugins.add(plugin)
    }

    fun remove(pluginName: String) = synchronized(plugins) {
        plugins.removeAll { it.name == pluginName }
    }

    fun execute(event: BaseEvent): BaseEvent? = synchronized(plugins) {
        var result: BaseEvent? = event

        plugins.forEach { plugin ->
            if (result != null) {
                when (plugin) {
                    is DestinationPlugin -> {
                        plugin.process(result)
                    }
                    is EventPlugin -> {
                        when (result) {
                            is IdentifyEvent -> {
                                result = plugin.identify(result as IdentifyEvent)
                            }
                            is TrackEvent -> {
                                result = plugin.track(result as TrackEvent)
                            }
                            is GroupEvent -> {
                                result = plugin.group(result as GroupEvent)
                            }
                            is ScreenEvent -> {
                                result = plugin.screen(result as ScreenEvent)
                            }
                            is AliasEvent -> {
                                result = plugin.alias(result as AliasEvent)
                            }
                        }
                    }
                    else -> {
                        result = plugin.execute(result as BaseEvent)
                    }
                }
            }
        }
        return result
    }

    fun applyClosure(closure: (Plugin) -> Unit) = synchronized(plugins) {
        plugins.forEach {
            closure(it)
        }
    }

    fun find(pluginName: String): Plugin? = synchronized(plugins) {
        plugins.forEach {
            if (it.name == pluginName) {
                return it
            }
        }
        return null
    }
}