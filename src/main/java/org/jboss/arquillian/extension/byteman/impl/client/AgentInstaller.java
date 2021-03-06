/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.arquillian.extension.byteman.impl.client;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;

import com.sun.tools.attach.VirtualMachine;
import org.jboss.arquillian.config.descriptor.api.ArquillianDescriptor;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.core.api.annotation.Observes;
import org.jboss.arquillian.extension.byteman.impl.common.BytemanConfiguration;
import org.jboss.arquillian.extension.byteman.impl.common.GenerateScriptUtil;
import org.jboss.arquillian.test.spi.event.suite.BeforeSuite;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;

/**
 * AgentInstaller
 *
 * @author <a href="mailto:aslak@redhat.com">Aslak Knutsen</a>
 * @version $Revision: $
 */
public class AgentInstaller {
    @Inject
    private Instance<ArquillianDescriptor> descriptorInst;

    public void install(@Observes(precedence = 1) BeforeSuite event) {
        try {
            BytemanConfiguration config = BytemanConfiguration.from(descriptorInst.get());

            if (!config.autoInstallAgent()) {
                return;
            }
            try {
                // Not only load it, but also attempt to check firstTime variable, since in embedded containers this might be the same jvm
                Class<?> mainClass = Thread.currentThread().getContextClassLoader().loadClass("org.jboss.byteman.agent.Main");
                if (!(Boolean) mainClass.getDeclaredField("firstTime").get(null)) {
                    return;
                }
            } catch (ClassNotFoundException e) {
                // Agent not loaded yet, move on
            }

            String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];

            File bytemanHome = File.createTempFile("byteman", "agent");
            bytemanHome.delete();
            bytemanHome.mkdir();

            File bytemanLib = new File(bytemanHome, "lib");
            bytemanLib.mkdirs();

            InputStream bytemanInputJar = ShrinkWrap.create(JavaArchive.class)
                .addPackages(true, "org.jboss.byteman")
                .setManifest(
                    new StringAsset("Manifest-Version: 1.0\n"
                        + "Created-By: Arquillian\n"
                        + "Implementation-Version: 0.0.0.Arq\n"
                        + "Premain-Class: org.jboss.byteman.agent.Main\n"
                        + "Agent-Class: org.jboss.byteman.agent.Main\n"
                        + "Can-Redefine-Classes: true\n"
                        + "Can-Retransform-Classes: true\n")).as(ZipExporter.class).exportAsInputStream();


            File bytemanJar = new File(bytemanLib, BytemanConfiguration.BYTEMAN_JAR);
            GenerateScriptUtil.copy(bytemanInputJar, new FileOutputStream(bytemanJar));

            VirtualMachine vm = VirtualMachine.attach(pid);
            String agentProperties = config.agentProperties();
            vm.loadAgent(bytemanJar.getAbsolutePath(), "listener:true,port:" + config.clientAgentPort() + (agentProperties != null ? ",prop:" + agentProperties : ""));
            vm.detach();
        } catch (IOException e) {
            throw new RuntimeException("Could not write byteman.jar to disk", e);
        } catch (Exception e) {
            throw new RuntimeException("Could not install byteman agent", e);
        }
    }
}
