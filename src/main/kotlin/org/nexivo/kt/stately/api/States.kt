package org.nexivo.kt.stately.api

import org.nexivo.kt.stately.dsl.equivalents
import rx.Observable
import rx.subjects.PublishSubject
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

abstract class States<S>
    where S: State<S>, S: Enum<S> {

    inner class WhenStateIs(vararg states: S) : ReadOnlyProperty<States<S>, Observable<Boolean>> {

        private var _subject: PublishSubject<Boolean>? = null
        private val _states:  List<S>                  = states.flatMap { it.equivalents() }
        private var _previous: Boolean                 = false

        override fun getValue(thisRef: States<S>, property: KProperty<*>): Observable<Boolean> {

            if (_subject == null) {

                _subject = PublishSubject.create()

                thisRef.source.state
                        .map { _states.contains(it) }
                        .filter { _previous != it }
                        .subscribe {
                            _previous = it

                            _subject?.onNext(it)
                        }
            }

            return _subject!!
        }
    }

    inner class WhenStateIsNot(vararg states: S) : ReadOnlyProperty<States<S>, Observable<Boolean>> {

        private val _states:  List<S>                  = states.flatMap { it.equivalents() }
        private var _subject: PublishSubject<Boolean>? = null
        private var _previous: Boolean                 = true

        override fun getValue(thisRef: States<S>, property: KProperty<*>): Observable<Boolean> {

            if (_subject == null) {

                _subject = PublishSubject.create()

                thisRef.source.state
                        .map { !_states.contains(it) }
                        .filter { _previous != it }
                        .subscribe {
                            _previous = it

                            _subject?.onNext(it)
                        }
            }

            return _subject!!
        }
    }

    abstract val source: Stateful<S>
}
