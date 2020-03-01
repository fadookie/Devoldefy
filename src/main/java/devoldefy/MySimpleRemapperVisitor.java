/*
 * Copyright (c) 2018 Minecrell (https://github.com/Minecrell)
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package devoldefy;

import org.cadixdev.bombe.analysis.InheritanceProvider;
import org.cadixdev.bombe.type.signature.MethodSignature;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.impl.model.AbstractClassMappingImpl;
import org.cadixdev.lorenz.model.ClassMapping;
import org.cadixdev.lorenz.model.FieldMapping;
import org.cadixdev.lorenz.model.MethodMapping;
import org.cadixdev.mercury.RewriteContext;
import org.cadixdev.mercury.analysis.MercuryInheritanceProvider;
import org.eclipse.jdt.core.dom.*;

import java.lang.reflect.Field;
import java.util.Map;

import static org.cadixdev.mercury.util.BombeBindings.convertSignature;

/**
 * Remaps only methods and fields.
 */
class MySimpleRemapperVisitor extends ASTVisitor {
    
    private Field AbstractClassMappingImpl_methods;
    
    final RewriteContext context;
    final MappingSet mappings;
    private final InheritanceProvider inheritanceProvider;
    
    MySimpleRemapperVisitor(RewriteContext context, MappingSet mappings) {
        this.context = context;
        this.mappings = mappings;
        this.inheritanceProvider = MercuryInheritanceProvider.get(context.getMercury());
        
        try {
            AbstractClassMappingImpl_methods = AbstractClassMappingImpl.class
                .getDeclaredField("methods");
            AbstractClassMappingImpl_methods.setAccessible(true);
        }
        catch (NoSuchFieldException e) {
            e.printStackTrace();
        }
    }
    
    final void updateIdentifier(SimpleName node, String newName) {
        if (!node.getIdentifier().equals(newName)) {
            this.context.createASTRewrite().set(
                node,
                SimpleName.IDENTIFIER_PROPERTY,
                newName,
                null
            );
        }
    }
    
    private void remapMethod(SimpleName node, IMethodBinding binding) {
        ITypeBinding declaringClass = binding.getDeclaringClass();
        ClassMapping<?, ?> classMapping = this.mappings.getOrCreateClassMapping(declaringClass.getBinaryName());
        
        if (binding.isConstructor()) {
            updateIdentifier(node, classMapping.getSimpleDeobfuscatedName());
        }
        else {
            classMapping.complete(this.inheritanceProvider, declaringClass);
            
            MethodSignature signature = convertSignature(binding);
            MethodMapping mapping =
                classMapping.getMethodMapping(signature).orElse(null);
            
            //qouteall changed
            if (mapping == null) {
                mapping = recoverMethodMapping(binding, classMapping);
                if (mapping != null) {
                    System.out.println("Recovered dubious mapping " + signature + "\n" + mapping);
                }
            }
            
            if (mapping == null) {
                return;
            }
            
            updateIdentifier(node, mapping.getDeobfuscatedName());
        }
    }
    
    private MethodMapping recoverMethodMapping(
        IMethodBinding binding,
        ClassMapping<?, ?> classMapping
    ) {
        MethodMapping mapping;
        try {
            Map<MethodSignature, MethodMapping> methodMap =
                (Map<MethodSignature, MethodMapping>) AbstractClassMappingImpl_methods.get(
                    classMapping
                );
            mapping = methodMap.entrySet().stream().filter(
                entry -> entry.getKey().getName().equals(binding.getName())
            ).findFirst().map(entry -> entry.getValue()).orElse(null);
        }
        catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return mapping;
    }
    
    private void remapField(SimpleName node, IVariableBinding binding) {
        if (!binding.isField()) {
            return;
        }
        
        ITypeBinding declaringClass = binding.getDeclaringClass();
        if (declaringClass == null) {
            return;
        }
        
        ClassMapping<?, ?> classMapping = this.mappings.getClassMapping(declaringClass.getBinaryName()).orElse(
            null);
        if (classMapping == null) {
            return;
        }
        
        FieldMapping mapping = classMapping.computeFieldMapping(convertSignature(binding)).orElse(
            null);
        if (mapping == null) {
            return;
        }
        
        updateIdentifier(node, mapping.getDeobfuscatedName());
    }
    
    protected void visit(SimpleName node, IBinding binding) {
        switch (binding.getKind()) {
            case IBinding.METHOD:
                remapMethod(node, ((IMethodBinding) binding).getMethodDeclaration());
                break;
            case IBinding.VARIABLE:
                remapField(node, ((IVariableBinding) binding).getVariableDeclaration());
                break;
        }
    }
    
    @Override
    public final boolean visit(SimpleName node) {
        IBinding binding = node.resolveBinding();
        if (binding != null) {
            visit(node, binding);
        }
        return false;
    }
    
}
