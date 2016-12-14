package org.nexivo.kt.stately

import org.nexivo.kt.specifics.flow.ifSet
import org.nexivo.kt.specifics.string.ifNotBlank
import org.nexivo.kt.stately.api.Action
import org.nexivo.kt.stately.api.State
import org.nexivo.kt.stately.api.StateType

class StateDefinitions<S, T>
    internal constructor()
    where S: State<S>, S: Enum<S>, T: Action {

    private typealias StatesDefined          = Map<S?, StateTriggers>
    private typealias Triggers               = MutableMap<T, S>
    private typealias StateTriggers          = Map<T, S>
    private typealias StateDefinitionBuilder = MutableMap<S?, StateTriggers>

    inner class StateBuilder internal constructor () {

        internal val triggers: Triggers = mutableMapOf()

        infix fun T.triggers(state: S): Unit {

            triggers.putIfAbsent(this, state)
        }
    }

    companion object {

        internal val DEFINITIONS: MutableMap<String, StateDefinitions<*, *>> = mutableMapOf()

        fun clearGlobalDefinitions(): Unit {
            DEFINITIONS.clear()
        }

        fun <S, T> removeDefinition(name: String): Unit
            where S: State<S>, S: Enum<S>, T: Action {

            DEFINITIONS.remove(name)
        }

        fun <S, T> states(init: StateDefinitions<S, T>.() -> Unit): StateDefinitions<S, T>
            where S: State<S>, S: Enum<S>, T: Action {

            val result: StateDefinitions<S, T> = StateDefinitions()

            result.init()

            result.name ifNotBlank {

                DEFINITIONS[result.name] ifSet {
                    throw ExceptionInInitializerError("State definition already exists!\nRemove it or clear all definitions first, in order to redefine state definitions!")
                }

                DEFINITIONS.put(result.name!!, result)
            }

/*
            if (!result.name.isNullOrBlank() && DEFINITIONS[result.name] != null) {
                throw ExceptionInInitializerError("State definition already exists!\nRemove it or clear all definitions first, in order to redefine state definitions!")
            }

            if (!result.name.isNullOrBlank()) {
                DEFINITIONS.put(result.name!!, result)
            }
*/

            return result
        }
    }

    private val _states: StateDefinitionBuilder = mutableMapOf()

    private var _initialState: S?      = null
    private var _startWith:    T?      = null
    private var _name:         String? = null

    var initialState: S
        get() = _initialState!!
        internal set(value) {

            if (_initialState != value) {

                if (value.type != StateType.Initial) {
                    throw IllegalArgumentException("$value is not an Initial State, it is of type ${value.type}!")
                }

                _initialState = value
            } else {
                throw IllegalArgumentException("Initial State is already set to $_initialState and can not be reset to $value!")
            }
        }

    var name: String?
        get() = _name
        internal set(value) {

            if (_name != value) {
                _name = value
            } else {
                throw IllegalArgumentException("Initial State is already set to $_initialState and can not be reset to $value!")
            }
        }

    var startsWith: T?
        get() = _startWith
        internal set(value) {

            if (startsWith != value) {
                _startWith = value
            } else {
                throw IllegalArgumentException("Starts With is already set to $_startWith and can not be reset to $value!")
            }
        }

    val states: StatesDefined by lazy {

        val result: StatesDefined = _states.toMap()

        _states.clear()

        result
    }

    fun initialState(state: () -> S): Unit {
        initialState = state()
    }

    fun name(name: () -> String): Unit {
        this.name = name()
    }

    fun startsWith(trigger: () -> T): Unit {
        startsWith = trigger()
    }

    infix fun S.transitions(init: StateBuilder.() -> Unit): Unit {

        _states.putIfAbsent(this, buildState(init))
    }

    infix fun Iterable<S>.states(init: StateBuilder.() -> Unit): Unit {

        val state = buildState(init)

        this.forEach {
            _states.putIfAbsent(it, state)
        }
    }

    infix fun S.`same as`(source: S): Unit {

        copyState(source) { it }
    }

    // todo, needs a better function name
    infix fun S.`similar to`(source: S): Unit {

        copyState(source) {
            triggers: StateTriggers ->

            triggers.filterValues { state: S -> state != this }.toMap()
        }
    }

    private fun buildState(init: StateBuilder.() -> Unit): StateTriggers {

        val initializer: StateBuilder = StateBuilder()

        initializer.init()

        return initializer.triggers.toMap()
    }

    private fun S.copyState(source: S, filtered: (StateTriggers) -> StateTriggers) {

        val state: StateTriggers? = _states[source]

        if (state != null) {
            _states.putIfAbsent(this, filtered(state))
        }
    }
}
