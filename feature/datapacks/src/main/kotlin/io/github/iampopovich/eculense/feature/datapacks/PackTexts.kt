package io.github.iampopovich.eculense.feature.datapacks

import io.github.iampopovich.eculense.core.datapacks.PackManifest
import io.github.iampopovich.eculense.core.ui.UiText

/** Built-in reference packs are localized via their resource ids; other packs show their own text as is. */
internal object PackTexts {
    fun title(m: PackManifest): UiText = if (m.titleRes != 0) UiText.Res(m.titleRes) else UiText.Raw(m.title)

    fun description(m: PackManifest): UiText = if (m.descriptionRes != 0) UiText.Res(m.descriptionRes) else UiText.Raw(m.description)

    fun license(m: PackManifest): UiText = if (m.licenseRes != 0) UiText.Res(m.licenseRes) else UiText.Raw(m.license)
}
