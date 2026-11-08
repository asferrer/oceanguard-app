package com.oceanguard.ai.data.contribution

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/** Debug builds: the debug provider prints a token to Logcat to register in the Firebase console. */
internal object AppCheckFactory {
    fun create(): AppCheckProviderFactory = DebugAppCheckProviderFactory.getInstance()
}
