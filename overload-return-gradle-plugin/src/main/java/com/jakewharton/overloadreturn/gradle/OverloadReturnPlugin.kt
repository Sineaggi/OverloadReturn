package com.jakewharton.overloadreturn.gradle

import com.android.build.gradle.AppPlugin
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.BasePlugin
import com.android.build.gradle.LibraryPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project

class OverloadReturnPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.plugins.withType(BasePlugin::class.java) {
      val extension = project.extensions.getByName("android") as BaseExtension
      extension.registerTransform(OverloadReturnTransform())
    }
  }
}
