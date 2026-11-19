package io.github.iampopovich.eculense.core.datapacks

/**
 * Pinned pack versions (checked 2026-11-18). To update a version, change the tag, size and sha256 here
 * (size and hash — `curl -sL <url> | sha256sum`; for GitHub releases the hash is shown on the release page).
 */
object PackCatalog {
    const val VEHICLES = "vehicles"
    const val OBDEX = "obdex"
    const val OEM_DTC = "oem-dtc"

    /** gor3a/vehicle-makes-models: weekly versioned releases; `vehicles.sqlite` is used unmodified. */
    val vehicles = PackManifest(
        id = VEHICLES,
        title = "Vehicle models and specs",
        description = "216 makes with models, generations and trims: displacement, power, tank and weight.",
        titleRes = R.string.pack_vehicles_title,
        descriptionRes = R.string.pack_vehicles_description,
        licenseRes = R.string.pack_vehicles_license,
        version = "v1.0.3",
        files = listOf(
            PackFile(
                "vehicles.sqlite",
                "https://github.com/gor3a/vehicle-makes-models/releases/download/v1.0.3/vehicles.sqlite",
                sizeBytes = 60_080_128,
                sha256 = "56b7a311caba0007c782c415d090aaa7aa0b7312d120bcc00d388241a49d70e2",
            ),
        ),
        license = "ODbL 1.0 (data), MIT (code)",
        licenseUrl = "https://opendatacommons.org/licenses/odbl/1-0/",
        sourceUrl = "https://github.com/gor3a/vehicle-makes-models",
        attribution = "Vehicle data: gor3a/vehicle-makes-models (data compiled from autoevolution.com), ODbL 1.0. " +
            "Downloaded unmodified from the official release.",
        update = UpdateSource.GithubRelease(
            "gor3a/vehicle-makes-models",
            mapOf("vehicles.sqlite" to "https://github.com/gor3a/vehicle-makes-models/releases/download/{tag}/vehicles.sqlite"),
        ),
    )

    /**
     * foerbsnavi/OBDex: no GitHub releases, the built JSON is published on GitHub Pages; version = `generated_at`
     * from meta.json. The hash is checked only for the pinned version (Pages serves the latest build).
     */
    val obdex = PackManifest(
        id = OBDEX,
        title = "Generic trouble codes (OBDex)",
        description = "9,533 generic OBD-II codes with descriptions, causes and symptoms (in English).",
        titleRes = R.string.pack_obdex_title,
        descriptionRes = R.string.pack_obdex_description,
        licenseRes = R.string.pack_obdex_license,
        version = "2026-11-07T13:44:03.324Z",
        files = listOf(
            PackFile(
                "generic.min.json",
                "https://foerbsnavi.github.io/OBDex/generic.min.json",
                sizeBytes = 31_739_947,
                sha256 = "b160e9ea689a1c0aebb67867c7b8c98c0287897ef929efc071683b6a27364a71",
            ),
        ),
        license = "CC0 1.0 (data), MIT (tools)",
        licenseUrl = "https://creativecommons.org/publicdomain/zero/1.0/",
        sourceUrl = "https://github.com/foerbsnavi/OBDex",
        attribution = "OBD-II code data: obdex (https://github.com/foerbsnavi/obdex), CC0-1.0",
        update = UpdateSource.JsonField("https://foerbsnavi.github.io/OBDex/meta.json", "generated_at"),
    )

    /** Wal33D/dtc-database: the SQLite file lives in the repository, release v0.1.0 has no assets → raw link to the tag. */
    val oemDtc = PackManifest(
        id = OEM_DTC,
        title = "Manufacturer trouble codes",
        description = "Manufacturer-specific code descriptions for 33 makes. Shown only for the active car's make.",
        titleRes = R.string.pack_oem_dtc_title,
        descriptionRes = R.string.pack_oem_dtc_description,
        version = "v0.1.0",
        files = listOf(
            PackFile(
                "dtc_codes.db",
                "https://raw.githubusercontent.com/Wal33D/dtc-database/v0.1.0/data/dtc_codes.db",
                sizeBytes = 3_256_320,
                sha256 = "099a4ffd60398112a0540b0bbc93a5929e05e7f4e6d4988ca2a50858af01b743",
            ),
        ),
        license = "MIT",
        licenseUrl = "https://github.com/Wal33D/dtc-database/blob/main/LICENSE",
        sourceUrl = "https://github.com/Wal33D/dtc-database",
        attribution = "DTC definitions: Wal33D/dtc-database, MIT License",
        update = UpdateSource.GithubRelease(
            "Wal33D/dtc-database",
            mapOf("dtc_codes.db" to "https://raw.githubusercontent.com/Wal33D/dtc-database/{tag}/data/dtc_codes.db"),
        ),
    )

    val all: List<PackManifest> = listOf(vehicles, obdex, oemDtc)

    fun byId(id: String): PackManifest? = all.firstOrNull { it.id == id }
}
