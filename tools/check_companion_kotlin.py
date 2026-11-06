#!/usr/bin/env python3
"""Type-compile only companion + real PageKit; not a Flutter/Gradle build or APK."""
import argparse
import concurrent.futures
import pathlib
import re
import subprocess
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
ARTIFACTS = [
    "org/jetbrains/kotlin/kotlin-compiler-embeddable/2.2.21/kotlin-compiler-embeddable-2.2.21.jar",
    "org/jetbrains/kotlin/kotlin-stdlib/2.2.21/kotlin-stdlib-2.2.21.jar",
    "org/jetbrains/kotlin/kotlin-script-runtime/2.2.21/kotlin-script-runtime-2.2.21.jar",
    "org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar",
    "org/jetbrains/kotlin/kotlin-daemon-embeddable/2.2.21/kotlin-daemon-embeddable-2.2.21.jar",
    "org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0/kotlinx-coroutines-core-jvm-1.8.0.jar",
    "org/jetbrains/annotations/13.0/annotations-13.0.jar",
    "org/robolectric/android-all/15-robolectric-12650502/android-all-15-robolectric-12650502.jar",
    "org/osmdroid/osmdroid-android/6.1.20/osmdroid-android-6.1.20.aar",
    "com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar",
    "com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar",
]


def main():
    import os
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", required=True, type=pathlib.Path, help="Outside the repository, e.g. F:/NagiDev/companion-check")
    args = parser.parse_args()
    cache = args.cache.resolve()
    cache.mkdir(parents=True, exist_ok=True)

    def fetch(path):
        target = cache / path.split("/")[-1]
        if not target.exists():
            partial = target.with_suffix(target.suffix + ".part")
            urllib.request.urlretrieve("https://repo.maven.apache.org/maven2/" + path, partial)
            partial.replace(target)
        return target

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(fetch, ARTIFACTS))
    with zipfile.ZipFile(cache / "osmdroid-android-6.1.20.aar") as archive:
        (cache / "osmdroid.jar").write_bytes(archive.read("classes.jar"))
    src = ROOT / "android/app/src/main/kotlin/com/github/kyosee/venera"
    page = (src / "suite/SuitePages.kt").read_text()
    (cache / "PageKit.kt").write_text(page[:page.index('/**\n * 「更多」页')])
    panels = (src / "suite/SuitePanels.kt").read_text()
    colors = [re.search(r"internal const val " + key + r" = [^\n]+", panels).group() for key in ("BG", "CARD", "TEXT", "SUB", "PINK")]
    (cache / "SuitePanels.kt").write_text("package io.github.kyosee.venera.suite\nobject SuitePanels {\n" + "\n".join(colors) + "\n}")
    (cache / "SuiteNavAdapter.kt").write_text("package io.github.kyosee.venera.suite\nobject SuiteNav { fun attach(a: android.app.Activity, key: String) {} ; fun interceptFinish(a: android.app.Activity): Boolean = false }")
    cp = os.pathsep.join(str(path) for path in cache.glob("*.jar"))
    sources = list((src / "companion").glob("*.kt")) + [cache / "PageKit.kt", cache / "SuitePanels.kt", cache / "SuiteNavAdapter.kt"]
    result = subprocess.run(["java", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                             "-jvm-target", "17", "-classpath", cp, "-d", str(cache / "classes")] + [str(p) for p in sources],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (cache / "compile.log").write_text(result.stdout)
    print(result.stdout)
    if result.returncode == 0:
        print("Companion Kotlin type compilation passed. Full Gradle build and device tests are still required.")
    raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
