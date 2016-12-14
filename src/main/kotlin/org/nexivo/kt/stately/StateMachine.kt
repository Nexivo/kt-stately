package org.nexivo.kt.stately

import org.nexivo.kt.specifics.flow.ifNotSetTo
import org.nexivo.kt.specifics.flow.ifNotSet
import org.nexivo.kt.specifics.flow.ifSet
import org.nexivo.kt.specifics.string.ifBlank
import org.nexivo.kt.stately.api.Action
import org.nexivo.kt.stately.api.State
import org.nexivo.kt.stately.api.StateHandlerResult
import org.nexivo.kt.stately.api.StateType
import rx.Observable
import rx.Subscription
import rx.subjects.PublishSubject
import java.io.Closeable
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class StateMachine<S, T>
    internal constructor(private val subject: PublishSubject<S>)
        : Observable<S>({ subject.subscribe(it) }), Closeable
        where S: State<S>, S: Enum<S>, T: Action {

    private typealias StateHandlers         = MutableMap<S, StateHandling>
    private typealias ExplicitStateHandlers = MutableMap<S, StateHandler>
    private typealias TriggerGuard          = (S) -> Boolean
    private typealias TriggerGuards         = MutableMap<T, TriggerGuard>
    private typealias StateHandler          = (StateTransition<S, T>) -> StateHandlerResult
    private typealias StatesDefined         = StateDefinitions<S, T>
    private typealias Triggers              = Set<T>

    data class StateTransition<S, out T> internal constructor(val reason: T, val from: S, val to: S?)
        where S: State<S>, S: Enum<S>, T: Action

    inner class StateHandling internal constructor() {

        private var _handleOnEnters: ExplicitStateHandlers? = null
        private var _handleOnExists: ExplicitStateHandlers? = null

        fun onEnter(from: S? = null, body: StateHandler): Unit {

            _handleOnEnters ifNotSet {
                _handleOnEnters = mutableMapOf()
            }

            _handleOnEnters!!.putIfAbsent(from, body)
        }

        fun onExit(to: S? = null, body: StateHandler): Unit {

            _handleOnExists ifNotSet {
                _handleOnExists = mutableMapOf()
            }

            _handleOnExists!!.putIfAbsent(to, body)
        }

        fun  exitHandler(to: S):     StateHandler? = _handleOnExists?.get(to)   ?: _handleOnExists?.get(null as S?)

        fun  enterHandler(from: S?): StateHandler? = _handleOnEnters?.get(from) ?: _handleOnEnters?.get(null as S?)
    }

    inner class  StateTriggerExitException internal constructor(val trigger: T, val from: S?, val to: S)
        : Throwable("Exiting state $from caused an exception transitioning to $to while trying to $trigger!")

    inner class  StateTriggerEnterException internal constructor(val trigger: T, val from: S?, val to: S)
        : Throwable("Entering state $to caused an exception transitioning from $from while trying to $trigger!")

    inner class StateTriggerException internal constructor(val trigger: T, val state: S?)
        : Throwable("Invalid $trigger trigger for current $state state!")

    companion object {

        fun <S, T> machine(init: StateMachine<S, T>.() -> Unit): StateMachine<S, T>
            where S: State<S>, S: Enum<S>, T: Action {

            val result: StateMachine<S, T> = StateMachine(PublishSubject.create())

            result.init()

            return result
        }
    }

    private val _handlers:      StateHandlers = mutableMapOf()
    private val _guardHandlers: TriggerGuards = mutableMapOf()
    private val _triggerQueue: Queue<T> = ArrayDeque<T>()

    @Suppress("UNCHECKED_CAST")
    private val _defined: StatesDefined by lazy {
       _customStates
            ?: StateDefinitions.DEFINITIONS[_definition] as StateDefinitions<S, T>?
            ?: throw IllegalArgumentException("State Machines must have Machine States defined!")
    }

    private val _subscriptions: MutableList<Subscription> by lazy { mutableListOf<Subscription>() }

    private var _customStates: StatesDefined? = null
    private var _definition:   String         = ""
    private var _midTrigger:   AtomicBoolean  = AtomicBoolean(false)
    private var _state:        S?             = null
    private var _unhandled:    StateHandler?  = null

    val state: S get() = _state!!

    val triggers: Triggers? get() = _defined.states[state]?.keys

    fun initiate(): Unit {

        if (_state == null && setState(_defined.initialState) && _defined.startsWith != null) {
            this trigger _defined.startsWith!!
        }
    }

    fun definitions(init: () -> StateDefinitions<S, T>): Unit {

        val value = init()

        _customStates.ifNotSetTo(value) {
            throw IllegalArgumentException("States definition have been assigned and can not be reassigned!")
        }

        _customStates = value
    }

    fun use(definition: () -> String): Unit {

        val value = definition()

        value ifBlank {
            throw IllegalArgumentException("Definition can not be NULL nor Blank!")
        }

        _definition.ifNotSetTo(value) {
            throw IllegalArgumentException("Definition is already set to \"$_definition\" and can not be reset to \"$value\"!")
        }

        if (!StateDefinitions.DEFINITIONS.containsKey(value)) {
            throw IllegalArgumentException("\"$value\" state definitions do not exist!")
        }

        _definition = value
    }

    infix fun T.`only if`(predicate: TriggerGuard): Unit {
        _guardHandlers.putIfAbsent(this, predicate)
    }

    fun unhandled (body: StateHandler): Unit {
        _unhandled = body
    }

    infix fun <O: Any?> Observable<O>.react(autoAction: (O) -> T): Unit {

        _subscriptions.add(this.subscribe { this@StateMachine trigger autoAction(it) })
    }

    infix fun S.state(init: StateHandling.() -> Unit): Unit {

        val result: StateHandling = StateHandling()

        result.init()

        _handlers.putIfAbsent(this, result)
    }

    infix fun Iterable<S?>.state(init: StateHandling.() -> Unit): Unit {

        val result: StateHandling = StateHandling()

        result.init()

        this.forEach { _handlers.putIfAbsent(it, result) }
    }

    fun states(init: StatesDefined.() -> Unit): Unit {

        _customStates ifSet {
            throw IllegalArgumentException("Custom states definitions have been provided and can not be redefined!")
        }

        _customStates = StateDefinitions()

        _customStates!!.init()
    }

    infix fun `can trigger`(trigger: T): Boolean
        = nextState(trigger, guarded = true) != null

    infix fun `might trigger`(trigger: T): Boolean
        = nextState(trigger, guarded = false) != null

    override fun close() {
        finalize()
    }

    infix fun trigger(trigger: T): Boolean? {

        if (_midTrigger.get()) {
            synchronized(_triggerQueue) {

                _triggerQueue.add(trigger)

                return@trigger null
            }
        }

        _midTrigger.set(true)

        var result: Boolean? = processTrigger(trigger)

        do {
            val next: T? = synchronized(_triggerQueue) {

                if (_triggerQueue.size > 0) {
                    _triggerQueue.remove()
                } else {
                    null
                }
            }

            if (next == null) {
                _midTrigger.set(false)
            } else {
                result = null

                processTrigger(next)
            }
        } while (_midTrigger.get())

        return result
    }

    private fun finalize() {
        _subscriptions.forEach { it.unsubscribe() }
    }

    private fun nextState(trigger: T, guarded: Boolean = true): S? {

        val nextState: S? = _defined.states[state]?.get(trigger)

        if (nextState == null || (guarded && !(_guardHandlers[trigger]?.invoke(state) ?: true))) {
            return null
        }

        return nextState
    }

    private fun processTrigger(trigger: T): Boolean? {

        val nextState:  S?                    = nextState(trigger)
        val transition: StateTransition<S, T> = StateTransition(trigger, state, nextState)

        return if (nextState == null) {
            when (_unhandled?.invoke(transition) ?: StateHandlerResult.Fail) {

                StateHandlerResult.Continue -> true

                StateHandlerResult.Exception -> {
                    subject.onError(StateTriggerException(trigger, state))

                    false
                }

                StateHandlerResult.Fail -> false
            }
        } else {
            val exitHandler: StateHandler = _handlers[state]?.exitHandler(nextState) ?: { StateHandlerResult.Continue }

            when (exitHandler(transition)) {
                StateHandlerResult.Fail -> false

                StateHandlerResult.Exception -> {
                    subject.onError(StateTriggerExitException(trigger, state, nextState))

                    false
                }

                StateHandlerResult.Continue -> {
                    val enterHandler: StateHandler = _handlers[nextState]?.enterHandler(state) ?: { StateHandlerResult.Continue }

                    when (enterHandler(transition)) {
                        StateHandlerResult.Fail -> false

                        StateHandlerResult.Exception -> {
                            subject.onError(StateTriggerEnterException(trigger, state, nextState))

                            false
                        }

                        StateHandlerResult.Continue -> {
                            setState(nextState)
                        }
                    }
                }
            }
        }
    }

    private fun setState(newState: S): Boolean {

        _state = newState

        subject.onNext(newState)

        if (state.type == StateType.Final) {
            finalize()
        }

        return true
    }
}
