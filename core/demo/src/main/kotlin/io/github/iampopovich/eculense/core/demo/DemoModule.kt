package io.github.iampopovich.eculense.core.demo

import io.github.iampopovich.eculense.core.obd.TransportFactory
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/** Демо-режим: виртуальный ELM327, видимый в списке адаптеров как обычный транспорт. */
val demoModule = module {
    single(named(DemoTransportFactory.KIND)) { DemoTransportFactory() } bind TransportFactory::class
}
