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
package org.eclipse.che.ide.ext.git.client;

import org.eclipse.che.ide.api.resources.Project;
import org.eclipse.che.ide.api.vcs.VcsBranchPresenterProvider;
import org.eclipse.che.ide.ext.git.client.branch.BranchPresenter;

import javax.inject.Inject;

public class GitBranchPresenterProvider implements VcsBranchPresenterProvider {

    private final BranchPresenter branchPresenter;

    @Inject
    public GitBranchPresenterProvider(BranchPresenter branchPresenter) {
        this.branchPresenter = branchPresenter;
    }

    @Override
    public String getVcsName() {
        return "git";
    }

    @Override
    public void show(Project project) {
        branchPresenter.showBranches(project);
    }
}
