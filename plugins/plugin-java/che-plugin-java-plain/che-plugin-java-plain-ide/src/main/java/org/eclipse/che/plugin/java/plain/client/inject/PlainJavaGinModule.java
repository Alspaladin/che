/*******************************************************************************
 * Copyright (c) 2012-2017 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.plugin.java.plain.client.inject;

import com.google.gwt.inject.client.AbstractGinModule;
import com.google.gwt.inject.client.multibindings.GinMultibinder;

import org.eclipse.che.ide.api.extension.ExtensionGinModule;
import org.eclipse.che.ide.api.project.type.wizard.ProjectWizardRegistrar;
import org.eclipse.che.plugin.java.plain.client.wizard.PlainJavaProjectWizardRegistrar;

/**
 * @author Valeriy Svydenko
 */
@ExtensionGinModule
public class PlainJavaGinModule extends AbstractGinModule {
    @Override
    protected void configure() {
        GinMultibinder.newSetBinder(binder(), ProjectWizardRegistrar.class).addBinding().to(PlainJavaProjectWizardRegistrar.class);
    }
}
