package grails.boot.config

import grails.config.Settings
import grails.core.GrailsApplicationLifeCycle
import grails.spring.BeanBuilder
import grails.util.Environment
import grails.util.Holders
import groovy.transform.CompileStatic
import grails.core.DefaultGrailsApplication
import grails.core.GrailsApplication
import org.grails.core.lifecycle.ShutdownOperations
import org.grails.dev.support.GrailsSpringLoadedPlugin
import org.grails.spring.DefaultRuntimeSpringConfiguration
import grails.plugins.DefaultGrailsPluginManager
import grails.plugins.GrailsPluginManager
import org.springframework.beans.BeansException
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.event.ApplicationContextEvent
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.core.io.Resource
import org.springframework.util.ClassUtils

/**
 * A {@link BeanDefinitionRegistryPostProcessor} that enhances any ApplicationContext with plugin manager capabilities
 *
 * @author Graeme Rocher
 * @since 3.0
 */
@CompileStatic
class GrailsApplicationPostProcessor implements BeanDefinitionRegistryPostProcessor, ApplicationContextAware, ApplicationListener<ApplicationContextEvent> {

    static final boolean RELOADING_ENABLED = Environment.getCurrent().isReloadEnabled() && ClassUtils.isPresent("org.springsource.loaded.SpringLoaded", Thread.currentThread().contextClassLoader)

    final GrailsApplication grailsApplication
    final GrailsPluginManager pluginManager
    final GrailsApplicationLifeCycle lifeCycle


    GrailsApplicationPostProcessor(Class...classes) {
        this(null, classes)
    }

    GrailsApplicationPostProcessor(ApplicationContext applicationContext, Class...classes) {
        this(null, applicationContext, classes)
    }

    GrailsApplicationPostProcessor(GrailsApplicationLifeCycle lifeCycle, ApplicationContext applicationContext, Class...classes) {
        this.lifeCycle = lifeCycle
        grailsApplication = new DefaultGrailsApplication(classes as Class[])
        grailsApplication.applicationContext = applicationContext
        pluginManager = new DefaultGrailsPluginManager(grailsApplication)
        pluginManager.loadPlugins()
        pluginManager.applicationContext = applicationContext
        performGrailsInitializationSequence()
    }

    protected void performGrailsInitializationSequence() {
        pluginManager.doArtefactConfiguration()
        grailsApplication.initialise()
        pluginManager.registerProvidedArtefacts(grailsApplication)
    }

    @Override
    void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        def springConfig = new DefaultRuntimeSpringConfiguration()
        def application = grailsApplication
        def beanResources = application.mainContext.getResource("classpath:spring/resources.groovy")
        if(beanResources.exists()) {
            def bb = new BeanBuilder(null, springConfig, application.classLoader)
            bb.loadBeans(beanResources)
        }
        springConfig.setBeanFactory((ListableBeanFactory) registry)
        pluginManager.doRuntimeConfiguration(springConfig)

        if(lifeCycle) {
            def withSpring = lifeCycle.doWithSpring()
            if(withSpring) {
                def bb = new BeanBuilder(null, springConfig, application.classLoader)
                bb.beans withSpring
            }
        }

        springConfig.registerBeansWithRegistry(registry)
    }

    @Override
    void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        beanFactory.registerSingleton(GrailsApplication.APPLICATION_ID, grailsApplication)
        beanFactory.registerSingleton(GrailsPluginManager.BEAN_NAME, pluginManager)

        if(RELOADING_ENABLED) {
            GrailsSpringLoadedPlugin.register(pluginManager)
        }
    }

    @Override
    void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        pluginManager.setApplicationContext(applicationContext)
        grailsApplication.setMainContext(applicationContext)

        if(applicationContext instanceof ConfigurableApplicationContext) {
            def configurable = (ConfigurableApplicationContext) applicationContext
            configurable.addApplicationListener(this)
            configurable.environment.addActiveProfile( grailsApplication.getConfig().getProperty(Settings.PROFILE, String, "web"))
        }
    }

    @Override
    void onApplicationEvent(ApplicationContextEvent event) {
        def context = event.applicationContext

        if(event instanceof ContextRefreshedEvent) {
            pluginManager.setApplicationContext(context)
            pluginManager.doDynamicMethods()
            lifeCycle?.doWithDynamicMethods()
            pluginManager.doPostProcessing(context)
            lifeCycle?.doWithApplicationContext()
            Holders.pluginManager = pluginManager
        }
        else if(event instanceof ContextClosedEvent) {
            pluginManager.shutdown()
            lifeCycle?.onShutdown(source:pluginManager)
            ShutdownOperations.runOperations()
            Holders.clear()
            if(RELOADING_ENABLED) {
                GrailsSpringLoadedPlugin.unregister()
            }
        }
    }

}
