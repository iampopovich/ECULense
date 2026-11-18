package io.github.iampopovich.eculense

import io.github.iampopovich.eculense.feature.dashboard.dashboardModule
import org.koin.core.module.Module

/** Добавить/убрать фичу = добавить/убрать строку здесь и в settings.gradle.kts. */
val appModules: List<Module> = listOf(
    dashboardModule,
)
