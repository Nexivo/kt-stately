package org.nexivo.kt.stately.dsl

import org.nexivo.kt.stately.api.State

fun <S: State<S>> S.equivalents(): List<S>
    = listOf(this) + (this.compositeOf?.flatMap { it.equivalents() } ?: listOf())

// todo this is general dsl, move it
infix fun <T> T.or(other: T): Iterable<T>
    = listOf(this, other)

infix fun <T> Iterable<T>.or(other: T): Iterable<T>
    = this + other

infix fun <T> Iterable<T>.and(other: T): Iterable<T>
    = this + other
