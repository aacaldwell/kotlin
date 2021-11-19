/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.export

import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.JsAstUtils
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.defineProperty
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.jsAssignment
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.prototypeOf
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.jsElementAccess
import org.jetbrains.kotlin.ir.backend.js.utils.*
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.js.backend.ast.*
import org.jetbrains.kotlin.util.collectionUtils.filterIsInstanceAnd

class ExportModelToJsStatements(
    private val namer: IrNamer,
    private val backendContext: JsIrBackendContext,
    private val declareNewNamespace: (String) -> String
) {
    private val namespaceToRefMap = mutableMapOf<String, JsNameRef>()

    fun generateModuleExport(module: ExportedModule, internalModuleName: JsName): List<JsStatement> {
        return module.declarations.flatMap { generateDeclarationExport(it, JsNameRef(internalModuleName), esModules = false) }
    }

    fun generateDeclarationExport(
        declaration: ExportedDeclaration,
        namespace: JsExpression?,
        esModules: Boolean
    ): List<JsStatement> {
        return when (declaration) {
            is ExportedNamespace -> {
                require(namespace != null) { "Only namespaced namespaces are allowed" }
                val statements = mutableListOf<JsStatement>()
                val elements = declaration.name.split(".")
                var currentNamespace = ""
                var currentRef: JsExpression = namespace
                for (element in elements) {
                    val newNamespace = "$currentNamespace$$element"
                    val newNameSpaceRef = namespaceToRefMap.getOrPut(newNamespace) {
                        val varName = JsName(declareNewNamespace(newNamespace), false)
                        val namespaceRef = jsElementAccess(element, currentRef)
                        statements += JsVars(
                            JsVars.JsVar(
                                varName,
                                JsAstUtils.or(
                                    namespaceRef,
                                    jsAssignment(
                                        namespaceRef,
                                        JsObjectLiteral()
                                    )
                                )
                            )
                        )
                        JsNameRef(varName)
                    }
                    currentRef = newNameSpaceRef
                    currentNamespace = newNamespace
                }
                statements + declaration.declarations.flatMap { generateDeclarationExport(it, currentRef, esModules) }
            }

            is ExportedFunction -> {
                if (declaration.hasNotExportedParent()) {
                    return listOfNotNull(declaration.generatePrototypeAssignmentIn(declaration.ir.parentAsClass))
                }

                val name = namer.getNameForStaticDeclaration(declaration.ir)
                if (esModules) {
                    listOf(JsExport(name, alias = JsName(declaration.name, false)))
                } else {
                    if (namespace != null) {
                        listOf(
                            jsAssignment(
                                jsElementAccess(declaration.name, namespace),
                                JsNameRef(name)
                            ).makeStmt()
                        )
                    } else emptyList()
                }
            }

            is ExportedConstructor -> emptyList()
            is ExportedConstructSignature -> emptyList()

            is ExportedProperty -> {
                require(namespace != null) { "Only namespaced properties are allowed" }
//                val underlying: List<JsStatement> = declaration.exportedObject?.let {
//                    generateDeclarationExport(it, null, esModules)
//                } ?: emptyList()

                if (declaration.hasNotExportedParent()) {
                    return listOfNotNull(declaration.generatePrototypeAssignmentIn(declaration.ir!!.parentAsClass))
                }

                val getter = declaration.irGetter?.let { JsNameRef(namer.getNameForStaticDeclaration(it)) }
                val setter = declaration.irSetter?.let { JsNameRef(namer.getNameForStaticDeclaration(it)) }
                listOf(defineProperty(namespace, declaration.name, getter, setter).makeStmt()) // + underlying
            }

            is ErrorDeclaration -> emptyList()

            is ExportedObject -> {
                require(namespace != null) { "Only namespaced properties are allowed" }

                val newNameSpace = jsElementAccess(declaration.name, namespace)
                val getter = namer.getNameForStaticDeclaration(declaration.irGetter).makeRef()

                val exportedMembers = declaration.generateMembersDeclarations()
                val staticsExport = declaration.generateStaticDeclarations(newNameSpace, esModules)

                listOf(defineProperty(namespace, declaration.name, getter, null).makeStmt()) + exportedMembers + staticsExport
                    .wrapWithExportComment("'${declaration.name}' object")
            }

            is ExportedClass -> {
                if (declaration.isInterface) return emptyList()
                val newNameSpace = if (namespace != null)
                    jsElementAccess(declaration.name, namespace)
                else
                    JsNameRef(Namer.PROTOTYPE_NAME, declaration.name)
                val name = namer.getNameForStaticDeclaration(declaration.ir)
                val klassExport =
                    if (esModules) {
                        JsExport(name, alias = JsName(declaration.name, false))
                    } else {
                        if (namespace != null) {
                            jsAssignment(
                                newNameSpace,
                                JsNameRef(name)
                            ).makeStmt()
                        } else null
                    }

                val exportedMembers = declaration.generateMembersDeclarations()
                val staticsExport = declaration.generateStaticDeclarations(newNameSpace, esModules)

                val innerClassesAssignments = declaration.nestedClasses
                    .filter { it.ir.isInner }
                    .map { it.generateInnerClassAssignment(declaration) }

                (listOfNotNull(klassExport) + exportedMembers + staticsExport + innerClassesAssignments)
                    .wrapWithExportComment("'$name' class")
            }
        }
    }

    private fun ExportedClassLike.generateStaticDeclarations(newNameSpace: JsExpression, esModules: Boolean): List<JsStatement> {
        // These are only used when exporting secondary constructors annotated with @JsName
        val staticFunctions = members
            .filter { it is ExportedFunction && it.isStatic }
            .takeIf { !ir.isInner }.orEmpty()

        // Nested objects are exported as static properties
        val staticProperties = members.mapNotNull {
            (it as? ExportedObject)?.takeIf { !it.ir.isInner }
                ?: (it as? ExportedProperty)?.takeIf { it.isStatic }
        }

        return (staticFunctions + staticProperties + nestedClasses)
            .flatMap { generateDeclarationExport(it, newNameSpace, esModules) }
    }

    private fun ExportedClassLike.generateMembersDeclarations(): List<JsStatement> {
        return members
            .mapNotNull { member ->
                when (member) {
                    is ExportedProperty -> member.generatePrototypeAssignmentIn(ir)
                    is ExportedFunction -> member.takeIf { !it.isStatic }
                        ?.let { it.generatePrototypeAssignmentIn(ir) }
                    else -> null
                }
            }
    }

    private fun ExportedFunction.generatePrototypeAssignmentIn(owner: IrClass): JsStatement? {
        if (ir.hasStableJsName() || namer.isDeclarationEliminated(owner)) return null

        val classPrototype = owner.prototypeRef()
        val currentFunctionExportedName = ir.getJsNameOrKotlinName().asString()
        val currentFunctionKotlinName = namer.getNameForMemberFunction(ir.realOverrideTarget)

        val originalFunctionInvocation = JsInvocation(
            JsNameRef(currentFunctionKotlinName, JsThisRef()),
            parameters.map { JsNameRef(it.name) }
        )

        val proxyFunction = JsFunction(emptyScope, JsBlock(), "proxy function for '$name' function")
            .apply {
                body.statements += JsReturn(originalFunctionInvocation)
                parameters += this@generatePrototypeAssignmentIn.parameters.map {
                    JsParameter(JsName(it.name, false))
                }
            }

        return jsAssignment(
            jsElementAccess(currentFunctionExportedName, classPrototype),
            proxyFunction,
        ).makeStmt()
    }

    private fun ExportedProperty.generatePrototypeAssignmentIn(owner: IrClass): JsStatement? {
        if (owner.isInterface || owner.isEnumEntry || namer.isDeclarationEliminated(owner)) {
            return null
        }

        val property = ir ?: return null
        val classPrototypeRef = owner.prototypeRef()

        if (property.getter?.extensionReceiverParameter != null || property.setter?.extensionReceiverParameter != null) {
            return null
        }

        if (property.isFakeOverride && !property.isEnumFakeOverriddenDeclaration(backendContext)) {
            return null
        }

        val overriddenSymbols = property.getter?.overriddenSymbols.orEmpty()

        // Don't generate `defineProperty` if the property overrides a property from an exported class,
        // because we've already generated `defineProperty` for the base class property.
        // In other words, we only want to generate `defineProperty` once for each property.
        // The exception is case when we override val with var,
        // so we need regenerate `defineProperty` with setter.
        // P.S. If the overridden property is owned by an interface - we should generate defineProperty
        // for overridden property in the first class which override those properties
        val hasOverriddenExportedInterfaceProperties = overriddenSymbols.any { it.owner.parentClassOrNull.isExportedInterface() }
                && !overriddenSymbols.any { it.owner.parentClassOrNull.isExportedClass() }

        val getterOverridesExternal = property.getter?.overridesExternal() == true
        val overriddenExportedGetter = !property.getter?.overriddenSymbols.isNullOrEmpty() &&
                property.getter?.isOverriddenExported(backendContext) == true

        val noOverriddenExportedSetter = property.setter?.isOverriddenExported(backendContext) == false

        val needsOverride = (overriddenExportedGetter && noOverriddenExportedSetter) ||
                property.isEnumFakeOverriddenDeclaration(backendContext)

        if (
            !owner.isExported(backendContext) ||
            (overriddenSymbols.isNotEmpty() && !needsOverride) &&
            !hasOverriddenExportedInterfaceProperties &&
            !getterOverridesExternal &&
            property.getJsName() == null
        ) {
            return null
        }

        // Use "direct dispatch" for final properties, i. e. instead of this:
        //     Object.defineProperty(Foo.prototype, 'prop', {
        //         configurable: true,
        //         get: function() { return this._get_prop__0_k$(); },
        //         set: function(v) { this._set_prop__a4enbm_k$(v); }
        //     });
        // emit this:
        //     Object.defineProperty(Foo.prototype, 'prop', {
        //         configurable: true,
        //         get: Foo.prototype._get_prop__0_k$,
        //         set: Foo.prototype._set_prop__a4enbm_k$
        //     });

        val getterForwarder = property.getter
            .takeIf { it.shouldExportAccessor() }
            .getOrGenerateIfFinal(classPrototypeRef) {
                propertyAccessorForwarder("getter forwarder") {
                    JsReturn(JsInvocation(it))
                }
            }

        val setterForwarder = property.setter
            .takeIf { it.shouldExportAccessor() }
            .getOrGenerateIfFinal(classPrototypeRef) {
                val setterArgName = JsName("value", false)
                propertyAccessorForwarder("setter forwarder") {
                    JsInvocation(it, JsNameRef(setterArgName)).makeStmt()
                }?.apply {
                    parameters.add(JsParameter(setterArgName))
                }
            }

        return JsExpressionStatement(
            defineProperty(
                classPrototypeRef,
                namer.getNameForProperty(property).ident,
                getter = getterForwarder,
                setter = setterForwarder
            )
        )
    }

    private fun IrClass.prototypeRef(): JsNameRef {
        val classRef = namer.getNameForStaticDeclaration(this).makeRef()
        return prototypeOf(classRef)
    }

    private fun IrSimpleFunction.propertyAccessorForwarder(
        description: String,
        callActualAccessor: (JsNameRef) -> JsStatement
    ): JsFunction? =
        when (visibility) {
            DescriptorVisibilities.PRIVATE -> null
            else -> JsFunction(
                emptyScope,
                JsBlock(callActualAccessor(JsNameRef(namer.getNameForMemberFunction(this), JsThisRef()))),
                description
            )
        }

    private fun IrSimpleFunction.accessorRef(classPrototypeRef: JsNameRef): JsNameRef? =
        when (visibility) {
            DescriptorVisibilities.PRIVATE -> null
            else -> JsNameRef(
                namer.getNameForMemberFunction(this),
                classPrototypeRef
            )
        }

    private fun IrSimpleFunction.overridesExternal(): Boolean {
        return isEffectivelyExternal() || overriddenSymbols.any { it.owner.overridesExternal() }
    }

    private inline fun IrSimpleFunction?.getOrGenerateIfFinal(
        classPrototypeRef: JsNameRef,
        generateFunc: IrSimpleFunction.() -> JsFunction?
    ): JsExpression? {
        if (this == null) return null
        return if (modality == Modality.FINAL) accessorRef(classPrototypeRef) else generateFunc()
    }

    private fun IrSimpleFunction?.shouldExportAccessor(): Boolean {
        if (this == null) return false

        if (parentAsClass.isExported(backendContext)) return true

        val property = correspondingPropertySymbol!!.owner

        if (property.isOverriddenExported(backendContext)) {
            return isOverriddenExported(backendContext)
        }

        return overridesExternal() || property.getJsName() != null
    }

    private fun ExportedClass.generateInnerClassAssignment(outerClass: ExportedClassLike): JsStatement {
        val innerClassRef = namer.getNameForStaticDeclaration(ir).makeRef()
        val outerClassRef = namer.getNameForStaticDeclaration(outerClass.ir).makeRef()
        val companionObject = ir.companionObject()
        val secondaryConstructors = members.filterIsInstanceAnd<ExportedFunction> { it.isStatic }
        val bindConstructor = JsName("__bind_constructor_", false)

        val blockStatements = mutableListOf<JsStatement>(
            JsVars(JsVars.JsVar(bindConstructor, innerClassRef.bindToThis()))
        )

        if (companionObject != null) {
            val companionName = companionObject.getJsNameOrKotlinName().identifier
            blockStatements.add(
                jsAssignment(
                    JsNameRef(companionName, bindConstructor.makeRef()),
                    JsNameRef(companionName, innerClassRef),
                ).makeStmt()
            )
        }

        secondaryConstructors.forEach {
            val currentFunRef = namer.getNameForStaticDeclaration(it.ir).makeRef()
            val assignment = jsAssignment(
                JsNameRef(it.name, bindConstructor.makeRef()),
                currentFunRef.bindToThis()
            ).makeStmt()

            blockStatements.add(assignment)
        }

        blockStatements.add(JsReturn(bindConstructor.makeRef()))

        return defineProperty(
            prototypeOf(outerClassRef),
            name,
            JsFunction(
                emptyScope,
                JsBlock(*blockStatements.toTypedArray()),
                "inner class '$name' getter"
            ),
            null
        ).makeStmt()
    }

    private fun ExportedProperty.hasNotExportedParent(): Boolean {
        return ir?.parentClassOrNull?.isExported(backendContext) == false
    }

    private fun ExportedFunction.hasNotExportedParent(): Boolean {
        return ir.parentClassOrNull?.isExported(backendContext) == false
    }

    private fun JsNameRef.bindToThis(): JsInvocation {
        return JsInvocation(
            JsNameRef("bind", this),
            JsNullLiteral(),
            JsThisRef()
        )
    }

    private fun List<JsStatement>.wrapWithExportComment(header: String): List<JsStatement> {
        return listOf(JsSingleLineComment("export: $header")) + this + listOf(JsSingleLineComment("end export"))
    }
}
