package org.codehaus.groovy.grails.project.plugins

import groovy.transform.CompileStatic
import grails.util.BuildSettings
import grails.util.PluginBuildSettings
import org.codehaus.groovy.grails.cli.api.BaseSettingsApi
import grails.util.Holders
import org.codehaus.groovy.grails.plugins.GrailsPluginManager
import org.codehaus.groovy.grails.commons.DefaultGrailsApplication
import org.codehaus.groovy.grails.plugins.ProfilingGrailsPluginManager
import org.codehaus.groovy.grails.plugins.DefaultGrailsPluginManager
import org.apache.commons.io.FilenameUtils
import org.codehaus.groovy.grails.plugins.GrailsPlugin
import org.codehaus.groovy.grails.plugins.GrailsPluginUtils
import org.codehaus.groovy.grails.commons.GrailsApplication
import grails.build.logging.GrailsConsole
import org.codehaus.groovy.grails.io.support.Resource

/**
 *
 * Loads the PluginManager and sets appropriate state
 *
 * @author Graeme Rocher
 * @since 2.2
 */
@CompileStatic
class GrailsProjectPluginLoader extends BaseSettingsApi{

    private static final GrailsConsole grailsConsole = GrailsConsole.getInstance()
    GrailsApplication grailsApplication
    ClassLoader classLoader

    GrailsProjectPluginLoader(GrailsApplication grailsApplication, ClassLoader classLoader, BuildSettings buildSettings, boolean interactive) {
        super(buildSettings, interactive)
        this.grailsApplication = grailsApplication
        this.classLoader = classLoader
    }

    GrailsPluginManager loadPlugins() {
        if (Holders.pluginManager) {
            // Add the plugin manager to the binding so that it can be accessed from any target.
            return Holders.pluginManager
        }

        GrailsPluginManager pluginManager
        def pluginFiles = pluginSettings.getPluginDescriptorsForCurrentEnvironment()

        try {

            def application
            def pluginClasses = []
            profile("construct plugin manager with ${pluginFiles.inspect()}") {
                for (Resource plugin in pluginFiles) {
                    def className = plugin.file.name - '.groovy'
                    pluginClasses << classLoader.loadClass(className)
                }

                profile("creating plugin manager with classes ${pluginClasses}") {
                    if (grailsApplication == null) {
                        grailsApplication = new DefaultGrailsApplication(new Class[0], new GroovyClassLoader(classLoader))
                        Holders.grailsApplication = grailsApplication
                    }

                    if (enableProfile) {
                        pluginManager = new ProfilingGrailsPluginManager(pluginClasses as Class[], grailsApplication)
                    }
                    else {
                        pluginManager = new DefaultGrailsPluginManager(pluginClasses as Class[], grailsApplication)
                    }

                    pluginSettings.pluginManager = pluginManager
                }
            }

            profile("loading plugins") {
                buildEventListener.triggerEvent("PluginLoadStart", pluginManager)
                pluginManager.loadPlugins()
                Holders.setPluginManager(pluginManager)
                def baseDescriptor = pluginSettings.basePluginDescriptor
                if (baseDescriptor) {
                    def baseName = FilenameUtils.getBaseName(baseDescriptor.filename)
                    def plugin = pluginManager.getGrailsPluginForClassName(baseName)
                    if (plugin) {
                        plugin.basePlugin = true
                    }
                }
                if (pluginManager.failedLoadPlugins) {
                    List<String> pluginNames = pluginManager.failedLoadPlugins.collect { GrailsPlugin plugin -> plugin.getName() }
                    buildEventListener.triggerEvent("StatusError", "Error: The following plugins failed to load due to missing dependencies: ${pluginNames}")
                    for (GrailsPlugin p in pluginManager.failedLoadPlugins) {
                        println "- Plugin: ${p.getName()}"
                        println "   - Dependencies:"
                        for (depName in p.dependencyNames) {
                            GrailsPlugin depInfo = pluginManager.getGrailsPlugin(depName)
                            def specifiedVersion = p.getDependentVersion(depName)
                            def invalid = depInfo && GrailsPluginUtils.isValidVersion(depInfo.getVersion(), specifiedVersion) ? '' : '[INVALID]'
                            println "       ${invalid ? '!' :'-' } ${depName} (Required: ${specifiedVersion}, Found: ${depInfo?.getVersion() ?: 'Not Installed'}) ${invalid}"
                        }
                    }
                    exit(1)
                }

                pluginManager.doArtefactConfiguration()
                grailsApplication.initialise()

                buildEventListener.triggerEvent("PluginLoadEnd", [pluginManager])
                return pluginManager
            }
        }
        catch (Exception e) {
            grailsConsole.error "Error loading plugin manager: " + e.message , e
            exit(1)
        }
    }
}
