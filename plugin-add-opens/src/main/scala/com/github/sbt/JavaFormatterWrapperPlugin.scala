/*
 * Copyright 2015 sbt community
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.sbt

import scala.sys.process.Process
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext

import sbt._
import sbt.Keys._

//import com.github.sbt.JavaFormatterPlugin

object JavaFormatterWrapperPlugin extends AutoPlugin {
  override def requires = JavaFormatterPlugin

  override def trigger = allRequirements

  @transient
  private val javaFormatterWrapperSbtLauncher = taskKey[File]("")

  override def globalSettings =
    Seq(commands += javafmt, commands += javafmtCheck, commands += javafmtAll, commands += javafmtCheckAll)

  override def projectSettings = Def.settings(javaFormatterWrapperSbtLauncher := Def.taskDyn {
    val v = sbtVersion.value
    Def.task {
      val Seq(launcher) = getJarFiles("org.scala-sbt" % "sbt-launch" % v).value
      launcher
    }
  }.value)

  private val javafmtWrapperProp = "play.javafmt.wrapper"

  private val javafmtExports =
    Seq("api", "code", "file", "parser", "tree", "util").map { exportedPackage =>
      s"--add-exports=jdk.compiler/com.sun.tools.javac.${exportedPackage}=ALL-UNNAMED"
    }

  private def javafmtCommand(name: String, delegatedCommand: String): Command =
    Command.command(
      name,
      Help.more(
        name,
        s"Runs $delegatedCommand in a fresh sbt JVM with the required jdk.compiler module-opening flags")) { state =>
      if (sys.props.get(javafmtWrapperProp).contains("true")) {
        delegatedCommand :: state
      } else {
        val extracted = Project.extract(state)
        val base = extracted.get(ThisBuild / baseDirectory)
        val launcher = extracted.runTask(javaFormatterWrapperSbtLauncher, state)._2
        val props = scala.sys.props.filter(_._1 == "plugin.version").map { case (k, v) => s"-D${k}=${v}" }
        val sbtArgs: Seq[String] = Seq(
          Seq("java"),
          javafmtExports,
          Seq("-jar", launcher.getAbsolutePath, s"-D$javafmtWrapperProp=true"),
          props,
          Seq(name)).flatten

        println(sbtArgs)
        val exitCode = Await.result(
          Future {
            Process(sbtArgs, base).!
          }(using ExecutionContext.global),
          30.seconds)
        if (exitCode == 0) state else state.fail
      }
    }

  private val javafmt = javafmtCommand("javafmt", "javafmt")
  private val javafmtCheck = javafmtCommand("javafmtCheck", "javafmtCheck")
  private val javafmtAll = javafmtCommand("javafmtAll", "all javafmtAll")
  private val javafmtCheckAll = javafmtCommand("javafmtCheckAll", "all javafmtCheckAll")

  private def getJarFiles(module: ModuleID): Def.Initialize[Task[Seq[File]]] = Def.task {
    dependencyResolution.value
      .retrieve(
        dependencyId = module,
        scalaModuleInfo = scalaModuleInfo.value,
        retrieveDirectory = csrCacheDirectory.value,
        log = streams.value.log)
      .left
      .map(e => throw e.resolveException)
      .merge
      .distinct
  }
}
