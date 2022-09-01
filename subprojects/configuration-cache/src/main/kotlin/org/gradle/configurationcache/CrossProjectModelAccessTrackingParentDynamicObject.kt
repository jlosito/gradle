/*
 * Copyright 2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.configurationcache

import groovy.lang.MissingMethodException
import groovy.lang.MissingPropertyException
import org.gradle.api.InvalidUserCodeException
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.configuration.internal.UserCodeApplicationContext
import org.gradle.configurationcache.extensions.capitalized
import org.gradle.configurationcache.problems.ProblemsListener
import org.gradle.configurationcache.problems.PropertyKind
import org.gradle.configurationcache.problems.PropertyProblem
import org.gradle.configurationcache.problems.PropertyTrace
import org.gradle.configurationcache.problems.StructuredMessage
import org.gradle.configurationcache.problems.location
import org.gradle.internal.metaobject.DynamicInvokeResult
import org.gradle.internal.metaobject.DynamicObject
import java.util.Locale


class CrossProjectModelAccessTrackingParentDynamicObject(
    private val ownerProject: ProjectInternal,
    private val delegate: DynamicObject,
    private val referrerProject: ProjectInternal,
    private val problems: ProblemsListener,
    private val coupledProjectsListener: CoupledProjectsListener,
    private val userCodeContext: UserCodeApplicationContext,
) : DynamicObject {
    override fun hasMethod(name: String?, vararg arguments: Any?): Boolean {
        onAccess(MemberKind.METHOD, name)
        return delegate.hasMethod(name, *arguments)
    }

    override fun tryInvokeMethod(name: String?, vararg arguments: Any?): DynamicInvokeResult {
        onAccess(MemberKind.METHOD, name)
        return delegate.tryInvokeMethod(name, *arguments)
    }

    override fun hasProperty(name: String?): Boolean {
        onAccess(MemberKind.PROPERTY, name)
        return delegate.hasProperty(name)
    }

    override fun tryGetProperty(name: String?): DynamicInvokeResult {
        onAccess(MemberKind.PROPERTY, name)
        return delegate.tryGetProperty(name)
    }

    override fun trySetProperty(name: String?, value: Any?): DynamicInvokeResult {
        onAccess(MemberKind.PROPERTY, name)
        return delegate.trySetProperty(name, value)
    }

    override fun getProperties(): MutableMap<String, *> {
        onAccess(MemberKind.PROPERTY, null)
        return delegate.properties
    }

    override fun getMissingProperty(name: String?): MissingPropertyException {
        onAccess(MemberKind.PROPERTY, name)
        return delegate.getMissingProperty(name)
    }

    override fun setMissingProperty(name: String?): MissingPropertyException {
        onAccess(MemberKind.PROPERTY, name)
        return delegate.setMissingProperty(name)
    }

    override fun methodMissingException(name: String?, vararg params: Any?): MissingMethodException {
        onAccess(MemberKind.METHOD, name)
        return delegate.methodMissingException(name, *params)
    }

    override fun getProperty(name: String?): Any {
        onAccess(MemberKind.PROPERTY, name)
        return delegate.getProperty(name)
    }

    override fun setProperty(name: String?, value: Any?) {
        onAccess(MemberKind.PROPERTY, name)
        return delegate.setProperty(name, value)
    }

    override fun invokeMethod(name: String?, vararg arguments: Any?): Any {
        onAccess(MemberKind.METHOD, name)
        return delegate.invokeMethod(name, *arguments)
    }

    private
    enum class MemberKind {
        PROPERTY, METHOD
    }

    private
    fun onAccess(memberKind: MemberKind, memberName: String?) {
        val trace = run {
            val location = userCodeContext.location(null)
            when (memberKind) {
                MemberKind.PROPERTY -> {
                    if (memberName != null)
                        PropertyTrace.Property(PropertyKind.PropertyUsage, memberName, PropertyTrace.Project(referrerProject.path, location))
                    else location
                }

                // method lookup is more clear from the stack trace, so keep the minimal trace pointing to the location:
                MemberKind.METHOD -> location
            }
        }

        val message = StructuredMessage.build {
            text("Project ")
            reference(referrerProject.identityPath.toString())
            text(" cannot dynamically lookup a ")
            text(memberKind.name.lowercase(Locale.ENGLISH))
            text(" in the parent project ")
            reference(ownerProject.identityPath.toString())
        }
        val exception = InvalidUserCodeException(message.toString().capitalized())
        problems.onProblem(
            PropertyProblem(trace, message, exception, null)
        )
        coupledProjectsListener.onProjectReference(referrerProject.owner, ownerProject.owner)
    }
}
