package org.nexivo.kt.stately.api

import org.nexivo.kt.stately.api.types.CompositeStates

interface State<S>
    where S: State<S>, S: Enum<S> {

    val type: StateType

    val compositeOf: CompositeStates<S>?
}