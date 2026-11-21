OBDb signalset and test-case files (debug builds and tests only)
================================================================

The files below this folder are verbatim copies from the OBDb project (https://github.com/OBDb), licensed under
the Creative Commons Attribution-ShareAlike 4.0 International licence (CC BY-SA 4.0, full text in
LICENSE-CC-BY-SA-4.0.txt, https://creativecommons.org/licenses/by-sa/4.0/). Contents are unmodified; only the
file names changed: the character '|' of the OBDb test-case names was replaced by '_' (e.g.
«7E4.7EC.220101|fc=1.yaml» → «7E4.7EC.220101_fc=1.yaml») and the folders were flattened.

Attribution: © OBDb contributors, https://github.com/OBDb

| Local path                          | Source                                                                                   | Commit                                   |
|-------------------------------------|------------------------------------------------------------------------------------------|------------------------------------------|
| Kia-Niro-EV/default.json            | github.com/OBDb/Kia-Niro-EV signalsets/v3/default.json                                   | c3b53ffec3ee6f4612d695c5aa96df687946dd6f |
| Kia-Niro-EV/2020/*.yaml             | github.com/OBDb/Kia-Niro-EV tests/test_cases/2020/commands/                              | c3b53ffec3ee6f4612d695c5aa96df687946dd6f |
| Kia-Sorento/default.json            | github.com/OBDb/Kia-Sorento signalsets/v3/default.json                                   | 2bc49d81648a6da4cf565f756768feed656cc713 |
| Kia-Sorento/2017/*.yaml             | github.com/OBDb/Kia-Sorento tests/test_cases/2017/commands/                              | 2bc49d81648a6da4cf565f756768feed656cc713 |
| LICENSE-CC-BY-SA-4.0.txt            | github.com/OBDb/Kia-Niro-EV LICENSE (identical in Kia-Sorento)                           | c3b53ffec3ee6f4612d695c5aa96df687946dd6f |

Use in ECULense: :core:demo is a debug-only dependency (debugImplementation), so these files are part of debug
builds (the «Demo: Niro EV (OBDb)» adapter answers with the recorded frames) and of the JVM tests (test vectors for
the OBDb command planner and signal decoder); release builds contain none of them. The app itself downloads the
signalset of the user's car from OBDb on demand, unmodified.
