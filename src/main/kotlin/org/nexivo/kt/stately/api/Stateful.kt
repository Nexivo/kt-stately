package org.nexivo.kt.stately.api

import rx.Observable

interface Stateful<S>
    where S: State<S>, S: Enum<S> {

    val state: Observable<S>
}