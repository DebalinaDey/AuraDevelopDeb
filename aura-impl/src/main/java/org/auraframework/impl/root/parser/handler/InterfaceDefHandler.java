/*
 * Copyright (C) 2013 salesforce.com, inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.auraframework.impl.root.parser.handler;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.auraframework.Aura;
import org.auraframework.builder.RootDefinitionBuilder;
import org.auraframework.def.AttributeDef;
import org.auraframework.def.DefDescriptor;
import org.auraframework.def.InterfaceDef;
import org.auraframework.def.MethodDef;
import org.auraframework.def.ProviderDef;
import org.auraframework.def.RequiredVersionDef;
import org.auraframework.impl.root.AttributeDefImpl;
import org.auraframework.impl.root.RequiredVersionDefImpl;
import org.auraframework.impl.root.event.RegisterEventDefImpl;
import org.auraframework.impl.root.intf.InterfaceDefImpl;
import org.auraframework.impl.system.DefDescriptorImpl;
import org.auraframework.system.AuraContext;
import org.auraframework.system.Source;
import org.auraframework.throwable.quickfix.QuickFixException;
import org.auraframework.util.AuraTextUtil;

import com.google.common.collect.ImmutableSet;

/**
 */
public class InterfaceDefHandler extends RootTagHandler<InterfaceDef> {

    public static final String TAG = "aura:interface";

    private static final String ATTRIBUTE_PROVIDER = "provider";
    private static final String ATTRIBUTE_EXTENDS = "extends";

    protected static final Set<String> ALLOWED_ATTRIBUTES = ImmutableSet.of(ATTRIBUTE_EXTENDS,
            RootTagHandler.ATTRIBUTE_DESCRIPTION, RootTagHandler.ATTRIBUTE_API_VERSION, ATTRIBUTE_ACCESS);
    private static final Set<String> PRIVILEGED_ALLOWED_ATTRIBUTES = new ImmutableSet.Builder<String>().add(
            RootTagHandler.ATTRIBUTE_SUPPORT, ATTRIBUTE_PROVIDER).addAll(ALLOWED_ATTRIBUTES).build();

    private final InterfaceDefImpl.Builder builder = new InterfaceDefImpl.Builder();

    public InterfaceDefHandler() {
        super();
    }

    public InterfaceDefHandler(DefDescriptor<InterfaceDef> descriptor, Source<?> source, XMLStreamReader xmlReader) {
        super(descriptor, source, xmlReader);
        builder.events = new HashMap<>();
        builder.methods = new HashMap<>();
        if (source != null) {
            builder.setOwnHash(source.getHash());
        }
        builder.extendsDescriptors = new HashSet<>();
    }

    @Override
    public Set<String> getAllowedAttributes() {
        return isInPrivilegedNamespace ? PRIVILEGED_ALLOWED_ATTRIBUTES : ALLOWED_ATTRIBUTES;
    }

    @Override
    protected void handleChildTag() throws XMLStreamException, QuickFixException {
        String tag = getTagName();
        if (AttributeDefHandler.TAG.equalsIgnoreCase(tag)) {
            AttributeDefHandler<InterfaceDef> handler=new AttributeDefHandler<>(this, xmlReader, source);
            AttributeDefImpl attributeDef = handler.getElement();
            DefDescriptor<AttributeDef> attributeDesc = attributeDef.getDescriptor();
            //            if (builder.getAttributeDefs().containsKey(attributeDesc)) {
            //                tagError(
            //                        "There is already an attribute named '%s' on %s '%s'.",
            //                        handler.getParentHandler().getDefDescriptor(),
            //                        attributeDesc.getName(),
            //                        "%s", "%s"
            //                );
            //            }
            builder.addAttributeDef(attributeDesc,attributeDef);
        } else if (RequiredVersionDefHandler.TAG.equalsIgnoreCase(tag)) {
            RequiredVersionDefHandler<InterfaceDef> handler = new RequiredVersionDefHandler<>(this,xmlReader, source);
            RequiredVersionDefImpl requiredVersionDef = handler.getElement();
            DefDescriptor<RequiredVersionDef> requiredVersionDesc = requiredVersionDef.getDescriptor();
            if (builder.getRequiredVersionDefs().containsKey(requiredVersionDesc)) {
                tagError(
                        "There is already a namespace '%s' on %s '%s'.",
                        handler.getParentHandler().getDefDescriptor(),
                        requiredVersionDesc.getName(),
                        "%s", "%s"
                        );
            }
            builder.getRequiredVersionDefs().put(requiredVersionDesc, requiredVersionDef);
        } else if (RegisterEventHandler.TAG.equalsIgnoreCase(tag)) {
            RegisterEventDefImpl regDef = new RegisterEventHandler<>(this, xmlReader, source).getElement();
            builder.events.put(regDef.getAttributeName(), regDef);
        } else if (MethodDefHandler.TAG.equalsIgnoreCase(tag)) {
            MethodDef methodDef = new MethodDefHandler<>(this, xmlReader, source).getElement();
            builder.methods.put(methodDef.getDescriptor(), methodDef);
        } else {
            error("Found unexpected tag <%s>", tag);
        }
    }

    @Override
    protected void readAttributes() throws QuickFixException {
        super.readAttributes();
        AuraContext context = Aura.getContextService().getCurrentContext();
        context.pushCallingDescriptor(getDefDescriptor());
        try {
            String extendsNames = getAttributeValue(ATTRIBUTE_EXTENDS);
            if (extendsNames != null) {
                for (String extendsName : AuraTextUtil.splitSimple(",", extendsNames)) {
                    builder.extendsDescriptors.add(getDefDescriptor(extendsName.trim(), InterfaceDef.class));
                }
            }

            String providerName = getAttributeValue(ATTRIBUTE_PROVIDER);
            if (providerName != null) {
                List<String> providerNames = AuraTextUtil.splitSimpleAndTrim(providerName, ",", 0);
                for (String provider : providerNames) {
                    builder.addProvider(provider);
                }
            } else {
                String apexProviderName = String.format("apex://%s.%sProvider", defDescriptor.getNamespace(),
                        AuraTextUtil.initCap(defDescriptor.getName()));
                DefDescriptor<ProviderDef> apexDescriptor = DefDescriptorImpl.getInstance(apexProviderName,
                        ProviderDef.class);
                if (apexDescriptor.exists()) {
                    builder.addProvider(apexDescriptor.getQualifiedName());
                }
            }

            builder.setAccess(readAccessAttribute());
        } finally {
            context.popCallingDescriptor();
        }
    }

    @Override
    public String getHandledTag() {
        return TAG;
    }

    @Override
    protected InterfaceDef createDefinition() {
        builder.setDescriptor(getDefDescriptor());
        builder.setLocation(startLocation);
        return builder.build();
    }

    @Override
    protected void handleChildText() throws XMLStreamException, QuickFixException {
        String text = xmlReader.getText();
        if (!AuraTextUtil.isNullEmptyOrWhitespace(text)) {
            error("No literal text allowed in interface definition");
        }
    }

    @Override
    protected RootDefinitionBuilder<InterfaceDef> getBuilder() {
        return builder;
    }

}
