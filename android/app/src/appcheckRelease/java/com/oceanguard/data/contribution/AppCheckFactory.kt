package com.oceanguard.ai.data.contribution

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/** Release builds: Play Integrity. The debug provider is never compiled into a release APK. */
internal object AppCheckFactory {
    fun create(): AppCheckProviderFactory = PlayIntegrityAppCheckProviderFactory.getInstance()
}
