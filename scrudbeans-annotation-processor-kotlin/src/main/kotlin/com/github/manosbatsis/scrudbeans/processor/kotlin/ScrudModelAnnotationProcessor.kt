package com.github.manosbatsis.scrudbeans.processor.kotlin

import com.github.manosbatsis.kotlin.utils.ProcessingEnvironmentAware
import com.github.manosbatsis.scrudbeans.api.DtoMapper
import com.github.manosbatsis.scrudbeans.api.mdd.ScrudModelProcessorException
import com.github.manosbatsis.scrudbeans.api.mdd.annotation.model.ScrudBean
import com.github.manosbatsis.scrudbeans.api.mdd.model.IdentifierAdapter
import com.github.manosbatsis.scrudbeans.processor.kotlin.descriptor.EntityModelDescriptor
import com.github.manosbatsis.scrudbeans.processor.kotlin.descriptor.ModelDescriptor
import com.github.manosbatsis.scrudbeans.processor.kotlin.descriptor.ScrudModelDescriptor
import com.squareup.kotlinpoet.*
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.*
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.Name
import javax.lang.model.element.TypeElement
import javax.persistence.Entity
import javax.tools.StandardLocation

/**
 * Annotation processor that generates SCRUD components
 * for model annotated with @[ScrudBean]
 * and JPA specification predicate factories for models
 * annotated with @[Entity]
 */
@SupportedAnnotationTypes("com.github.manosbatsis.scrudbeans.api.mdd.annotation.model.ScrudBean")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
class ScrudModelAnnotationProcessor : AbstractProcessor(), ProcessingEnvironmentAware {

    companion object {
        private val log = LoggerFactory.getLogger(ScrudModelAnnotationProcessor.javaClass)
        const val BLOCK_FUN_NAME = "block"
        const val KAPT_KOTLIN_SCRUDBEANS_GENERATED_OPTION_NAME = "kapt.kotlin.vaultaire.generated"
        const val KAPT_KOTLIN_GENERATED_OPTION_NAME = "kapt.kotlin.generated"

        val TYPE_PARAMETER_STAR = WildcardTypeName.producerOf(Any::class.asTypeName().copy(nullable = true))
    }

    private var complete = false
    private val typeSpecBuilder by lazy { TypeSpecBuilder(processingEnv)}
    private lateinit var filer: Filer

    // Config properties, i.e. "application.properties" from the classpath
    private lateinit var configProps: Properties

    /** Implement [ProcessingEnvironmentAware.processingEnvironment] for access to a [ProcessingEnvironment] */
    override val processingEnvironment by lazy {
        processingEnv
    }


    val generatedSourcesRoot: String by lazy {
        processingEnv.options[KAPT_KOTLIN_SCRUDBEANS_GENERATED_OPTION_NAME]
                ?: processingEnv.options[KAPT_KOTLIN_GENERATED_OPTION_NAME]
                ?: throw IllegalStateException("Can't find the target directory for generated Kotlin files.")
    }

    val sourceRootFile by lazy {
        val sourceRootFile = File(generatedSourcesRoot)
        sourceRootFile.mkdir()
        sourceRootFile
    }

    /**
     * {@inheritDoc}
     */
    override fun process(annotations: Set<TypeElement>, roundEnv: RoundEnvironment): Boolean {
        // short-circuit if there are multiple rounds
        if (complete) {
            processingEnv.noteMessage { "Processor has already been executed, ignoring" }
            return true
        }
        processingEnv.noteMessage { "ScrudModelAnnotationProcessor processing started" }
        // Init a filer
        this.filer = processingEnv.filer
        // Load config/properties
        configProps = this.loadProperties()
        // Create JPA query predicate factories for each entity in the source path
        generateEntityPredicateFactories(roundEnv)
        // Create other SCRUD components for each model annotated with ScrudBean
        generateScrudComponents(roundEnv)
        // Claiming that annotations have been processed by this processor
        complete = true
        return true
    }

    /**
     * Create SCRUD components for the target model
     * @param roundEnv The current compilation round environment
     */
    private fun generateScrudComponents(roundEnv: RoundEnvironment) {
        val annotatedModels = roundEnv.getElementsAnnotatedWith(ScrudBean::class.java)
        val modelDescriptors = HashMap<Name, ScrudModelDescriptor>()
        processingEnv.noteMessage { "ScrudModelAnnotationProcessor found ${annotatedModels?.size ?: 0} annotated classes" }
        if (annotatedModels != null) {
            for (element in annotatedModels) {
                try {
                    if (element is TypeElement) {
                        // Parse model to something more convenient
                        val descriptor = ScrudModelDescriptor(processingEnv, element, configProps)
                        // Mappers for manual DTOs
                        generateDtoMappers(descriptor)
                        generateDto(descriptor)
                        createIdAdapters(descriptor)
                        createRepository(descriptor)
                        createService(descriptor)
                        createController(descriptor)
                    } else {
                        processingEnv.errorMessage { "Not an instance of TypeElement but annotated with ScrudBean: ${element.simpleName}" }
                    }
                } catch (e: ScrudModelProcessorException) {
                    processingEnv.errorMessage { "Failed processing ScrudBean annotation for: ${element.simpleName}: ${e.message}" }
                    throw e
                }

            }
        }
    }

    /**
     * Create a DTO source file
     * @param descriptor The target model descriptor
     * @return the written file
     */
    private fun generateDto(descriptor: ScrudModelDescriptor): FileSpec? {
        val typeSpec = typeSpecBuilder.dtoSpecBuilder(descriptor, sourceRootFile)
        return writeKotlinFile(descriptor, typeSpec, descriptor.packageName)

    }

    /**
     * Create JPA query predicate factories for each entity in the source path
     * @param roundEnv The current compilation round environment
     */
    private fun generateEntityPredicateFactories(roundEnv: RoundEnvironment) {
        val entities = roundEnv.getElementsAnnotatedWith(ScrudBean::class.java)
        for (element in entities) {
            try {
                if (element.getAnnotation(Entity::class.java) != null) {
                    if (element is TypeElement) {
                        processingEnv.noteMessage { "generateEntityPredicateFactories, processing element: ${element.simpleName}" }
                        val descriptor = EntityModelDescriptor(processingEnv, element)
                        createPredicateFactory(descriptor)
                    } else {
                        processingEnv.noteMessage { "Not an instance of TypeElement but annotated with ScrudBean: ${element.simpleName}" }
                    }
                }
            } catch (e: RuntimeException) {
                processingEnv.errorMessage { "Error generating components for element.simpleName ${e.message}: " }
                throw e
            } catch (e: ScrudModelProcessorException) {
                e.printStackTrace()
                processingEnv.errorMessage { "Error generating components for ${element.simpleName}: " + e.message }
                throw e
            }

        }
    }

    /**
     * Create a SCRUD REST controller source file
     * @param descriptor The target model descriptor
     * @return the written file
     */
    private fun createController(descriptor: ScrudModelDescriptor): FileSpec? {
        return if (ScrudBean.NONE != descriptor.scrudBean.controllerSuperClass)
            writeKotlinFile(descriptor, typeSpecBuilder.createController(descriptor), descriptor.parentPackageName + ".controller")
        else null
    }

    /**
     * Create [DtoMapper]s for the ScudBeans' target DTOs
     * @param descriptor The target model descriptor
     * @return the mapper files
     */
    private fun generateDtoMappers(descriptor: ScrudModelDescriptor): List<FileSpec?> {
        val files = LinkedList<FileSpec?>()
        log.debug("generateDtoMappers, dtoTypes (${descriptor.dtoTypes.size}): ${descriptor.dtoTypes}")
        descriptor.dtoTypes.forEach { dtoClass ->
            val typeSpec = typeSpecBuilder.createDtoMapper(descriptor, dtoClass)
            files.add(writeKotlinFile(
                    descriptor,
                    typeSpec,
                    descriptor.parentPackageName + ".mapper"))
        }
        return files
    }

    /**
     * Create SCRUD service source files
     * @param descriptor The target model descriptor
     * @return the written files: interface and implementation
     */
    private fun createService(descriptor: ScrudModelDescriptor): List<FileSpec?> {
        val files = LinkedList<FileSpec?>()
        // Ensure a service has not already been created
        val serviceQualifiedName = descriptor.parentPackageName +
                ".service." + descriptor.simpleName + "Service"
        val existing = processingEnv.elementUtils.getTypeElement(serviceQualifiedName)
        if (Objects.isNull(existing)) {
            files.add(createServiceInterface(descriptor))
            files.add(createServiceImpl(descriptor))
        } else {
            processingEnv.noteMessage { "createService: $serviceQualifiedName} already exists, skipping" }
        }
        return files
    }

    /**
     * Create a SCRUD service interface source file
     * @param descriptor The target model descriptor
     * @return the written file
     */
    private fun createServiceInterface(descriptor: ScrudModelDescriptor): FileSpec? {
        val typeSpec = typeSpecBuilder.createServiceInterface(descriptor)
        return writeKotlinFile(descriptor, typeSpec, descriptor.parentPackageName + ".service")

    }

    /**
     * Create a SCRUD service implementation source file
     * @param descriptor The target model descriptor
     * @return the written file
     */
    private fun createServiceImpl(descriptor: ScrudModelDescriptor): FileSpec? {
        val typeSpec = typeSpecBuilder.createServiceImpl(descriptor)
        return writeKotlinFile(descriptor, typeSpec, descriptor.parentPackageName + ".service")
    }

    /**
     * Create a SCRUD repository source file
     * @param descriptor The target model descriptor
     * @return the written file
     */
    private fun createRepository(descriptor: ScrudModelDescriptor): FileSpec? {
        val typeSpec = typeSpecBuilder.createRepository(descriptor)
        return writeKotlinFile(descriptor, typeSpec, descriptor.parentPackageName + ".repository")
    }

    /**
     * Create an [IdentifierAdapter] implementation
     * @param descriptor The target model descriptor
     * @return the written file
     */
    private fun createIdAdapters(descriptor: ScrudModelDescriptor): List<FileSpec> {
        return listOf(ClassName(descriptor.packageName, descriptor.simpleName),
                ClassName(descriptor.packageName, descriptor.simpleName + "Dto"))
                .mapNotNull {
                    writeKotlinFile(descriptor, typeSpecBuilder.createIdAdapter(it, descriptor), descriptor.packageName)
                }
    }

    /**
     * Create a JPA specification predicate factory source file
     * @param descriptor The target model descriptor
     * @return the written file
     */
    private fun createPredicateFactory(descriptor: EntityModelDescriptor): FileSpec? {
        val typeSpec = typeSpecBuilder.createPredicateFactory(descriptor)
        return writeKotlinFile(descriptor, typeSpec, descriptor.parentPackageName + ".specification")
    }

    /**
     * Write and return a source file for the given [TypeSpec]
     * @param typeSpec The target model type spec
     * @param descriptor The target model descriptor
     * @param packageName The target source file package
     * @return the written file
     */
    private fun writeKotlinFile(descriptor: ModelDescriptor, typeSpec: TypeSpec, packageName: String): FileSpec? {
        val fileObjectName = packageName + "." + typeSpec.name!!
        var file: FileSpec? = null
        try {
            val existing = processingEnv.elementUtils.getTypeElement(fileObjectName)
            if (existing == null) {
                processingEnv.noteMessage { "writeJavaFile for $fileObjectName" }
                file = FileSpec.builder(packageName, typeSpec.name!!)
                        .addComment("-------------------- DO NOT EDIT -------------------\n")
                        .addComment(" This file is automatically generated by scrudbeans,\n")
                        .addComment(" see https://manosbatsis.github.io/scrudbeans\n")
                        .addComment(" To edit this file, copy it to the appropriate package \n")
                        .addComment(" in your src/main/kotlin folder and edit there. \n")
                        .addComment("----------------------------------------------------")
                        .addType(typeSpec)
                        .build()
                file.writeTo(sourceRootFile)
            } else {
                processingEnv.noteMessage { "writeJavaFile: Skipping for $fileObjectName as it already exists" }
            }
        } catch (e: Exception) {
            processingEnv.noteMessage { "writeJavaFile: Error creating file for $fileObjectName: ${e.message}" }
            throw e
        }

        return file
    }

    private fun loadProperties(): Properties {
        var props = Properties()
        try {
            val fileObject = this.filer!!
                    .getResource(StandardLocation.CLASS_OUTPUT, "", "application.properties")
            props.load(fileObject.openInputStream())
            processingEnv.noteMessage { "loadProperties, props: $props" }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return props
    }

}
