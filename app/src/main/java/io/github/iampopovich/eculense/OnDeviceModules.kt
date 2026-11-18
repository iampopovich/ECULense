package io.github.iampopovich.eculense

import io.github.iampopovich.eculense.core.ailocal.aiLocalModule
import org.koin.core.module.Module

/**
 * Модель на телефоне (3.10, :core:ailocal): единственное место, где приложение знает о модуле. Убрать модуль =
 * вернуть здесь пустой список (+ include и зависимость app). Файл рассчитан на перенос в source set флейвора
 * (`cloud` — пустой список, `local` — этот), общий код типов :core:ailocal не видит.
 */
val onDeviceModules: List<Module> = listOf(aiLocalModule)
