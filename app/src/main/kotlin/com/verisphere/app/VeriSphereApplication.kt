package com.verisphere.app

import android.app.Application

/**
 * Application entry point. Holds the single AppContainer service-locator
 * graph (D4.1). Every Activity and Service obtains its collaborators via
 *
 *     (application as VeriSphereApplication).container
 *
 * NEVER via static singletons or `Context.applicationContext as MyApp`.
 *
 * Cold-start budget is < 1 s on a mid-range device (NFR3). Keep
 * onCreate() empty — heavy work is deferred via the container's lazy
 * fields and only happens on first access from the relevant call site.
 */
class VeriSphereApplication : Application() {

    val container: AppContainer by lazy { AppContainer(applicationContext) }
}
