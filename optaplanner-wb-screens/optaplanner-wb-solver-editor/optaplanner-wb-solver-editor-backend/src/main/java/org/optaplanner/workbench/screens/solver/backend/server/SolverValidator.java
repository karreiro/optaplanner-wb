/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.optaplanner.workbench.screens.solver.backend.server;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import javax.inject.Inject;

import com.google.common.base.Charsets;
import org.drools.compiler.kie.builder.impl.InternalKieModule;
import org.drools.compiler.kie.builder.impl.KieContainerImpl;
import org.drools.compiler.kie.builder.impl.KieModuleKieProject;
import org.guvnor.common.services.project.builder.service.BuildService;
import org.guvnor.common.services.shared.message.Level;
import org.guvnor.common.services.shared.validation.model.ValidationMessage;
import org.kie.api.KieServices;
import org.kie.api.runtime.KieContainer;
import org.kie.workbench.common.services.backend.builder.Builder;
import org.kie.workbench.common.services.backend.builder.LRUBuilderCache;
import org.kie.workbench.common.services.backend.validation.asset.NoProjectException;
import org.kie.workbench.common.services.backend.validation.asset.Validator;
import org.kie.workbench.common.services.shared.project.KieProject;
import org.kie.workbench.common.services.shared.project.KieProjectService;
import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.SolverFactory;
import org.uberfire.backend.vfs.Path;

public class SolverValidator {

    private static final long SMOKE_TEST_MILLISECONDS_SPENT_LIMIT = 3000;

    private static final Set<String> SMOKE_TEST_SUPPORTED_PROJECTS = new HashSet<>();

    private KieProjectService projectService;

    @Inject
    private LRUBuilderCache builderCache;

    @Inject
    private BuildService buildService;

    static {
        SMOKE_TEST_SUPPORTED_PROJECTS.add( "optacloud" );
    }

    public SolverValidator() {
    }

    @Inject
    public SolverValidator( final KieProjectService projectService ) {
        this.projectService = projectService;
    }

    public List<ValidationMessage> validate( final Path resourcePath,
                                             final String content ) {
        return validate( resourcePath, content, false );
    }

    public List<ValidationMessage> validateAndRun( final Path resourcePath,
                                                   final String content ) {
        return validate( resourcePath, content, true );
    }

    private List<ValidationMessage> validate( final Path resourcePath,
                                              final String content,
                                              final boolean runSolver ) {
        try {
            final KieProject kieProject = projectService.resolveProject( resourcePath );

            final List<ValidationMessage> validationMessages = validator().validate( resourcePath,
                                                                                     inputStream( content ) );

            if ( validationMessages.isEmpty() ) {
                return buildSolver( resourcePath,
                                    kieProject,
                                    runSolver );
            } else {
                return validationMessages;
            }
        } catch ( NoProjectException e ) {
            return new ArrayList<ValidationMessage>();
        }
    }

    private ByteArrayInputStream inputStream( final String content ) {
        return new ByteArrayInputStream( content.getBytes( Charsets.UTF_8 ) );
    }

    private List<ValidationMessage> buildSolver( final Path resourcePath,
                                                 final KieProject kieProject,
                                                 final boolean runSolver ) {
        final List<ValidationMessage> validationMessages = new ArrayList<>();
        final ValidationMessage validationMessage = createSolverFactory( resourcePath,
                                                                         kieProject,
                                                                         runSolver );
        if ( validationMessage != null ) {
            validationMessages.add( validationMessage );
        }

        return validationMessages;
    }

    private Validator validator() throws NoProjectException {
        return new Validator( projectService, buildService ) {
            @Override
            protected Predicate<ValidationMessage> fromValidatedPath( final Path path ) {
                return message -> true;
            }
        };
    }

    private ValidationMessage createSolverFactory( final Path resourcePath,
                                                   final KieProject kieWorkbenchProject,
                                                   final boolean runSolver ) {
        final Builder builder = builderCache.assertBuilder( kieWorkbenchProject );
        final InternalKieModule kieModule = (InternalKieModule) builder.getKieModule();
        final org.drools.compiler.kie.builder.impl.KieProject kieProject = new KieModuleKieProject( kieModule, null );
        final KieContainer kieContainer = new KieContainerImpl( kieProject, KieServices.Factory.get().getRepository() );

        try {

            final String solverConfigResource = getSolverConfigResource( resourcePath,
                                                                         kieWorkbenchProject );

            SolverFactory<Object> solverFactory = SolverFactory.createFromKieContainerXmlResource( kieContainer, solverConfigResource );

            if ( runSolver ) {
                String projectName = resolveProjectName( resourcePath );
                if ( !SMOKE_TEST_SUPPORTED_PROJECTS.contains( projectName ) ) {
                    throw new IllegalStateException( "Running a smoke test for project (" + projectName + ") is not supported." );
                }

                solverFactory.getSolverConfig().getTerminationConfig().shortenTimeMillisSpentLimit( SMOKE_TEST_MILLISECONDS_SPENT_LIMIT );
                if ( solverFactory.getSolverConfig().getScoreDirectorFactoryConfig().getKsessionName() == null ) {
                    solverFactory.getSolverConfig().getScoreDirectorFactoryConfig().setKsessionName( kieProject.getDefaultKieSession().getName() );
                }
                Solver<Object> solver = solverFactory.buildSolver();

                XStreamSolutionImporter solutionImporter = new XStreamSolutionImporter( kieContainer.getClassLoader() );
                Object solution = solutionImporter.read( getClass().getClassLoader().getResourceAsStream( "org/optaplanner/workbench/screens/solver/backend/server/solution/" + projectName + ".xml" ) );

                solver.solve( solution );
            } else {
                solverFactory.buildSolver();
            }

        } catch ( Exception e ) {
            e.printStackTrace();
            return make( e,
                         resourcePath );
        }

        return null;
    }

    private String resolveProjectName( final Path resourcePath ) {
        KieProject kieProject = projectService.resolveProject( resourcePath );
        if ( kieProject == null ) {
            throw new IllegalStateException( "Failed to resolve KieProject (" + kieProject + ")." );
        }
        return kieProject.getProjectName();
    }

    private String getSolverConfigResource( final Path resourcePath,
                                            final KieProject kieWorkbenchProject ) {
        return resourcePath.toURI().substring( kieWorkbenchProject.getRootPath().toURI().length() + "/src/main/resources/".length() );
    }

    private ValidationMessage make( final Exception e,
                                    final Path resourcePath ) {
        ValidationMessage message = new ValidationMessage();

        message.setId( 0 );
        message.setLevel( Level.ERROR );
        message.setPath( resourcePath );
        message.setText( e.getMessage() );

        return message;
    }

}
