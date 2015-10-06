package org.jboss.da.rest.reports;

import org.apache.maven.scm.ScmException;
import org.jboss.da.communication.CommunicationException;
import org.jboss.da.communication.aprox.api.AproxConnector;
import org.jboss.da.communication.aprox.api.AproxConnector.RepositoryManipulationStatus;
import org.jboss.da.communication.aprox.model.Repository;
import org.jboss.da.communication.model.GAV;
import org.jboss.da.communication.pom.PomAnalysisException;
import org.jboss.da.listings.api.service.BlackArtifactService;
import org.jboss.da.listings.api.service.WhiteArtifactService;
import org.jboss.da.reports.api.ArtifactReport;
import org.jboss.da.reports.api.ReportsGenerator;
import org.jboss.da.reports.api.SCMLocator;
import org.jboss.da.reports.api.VersionLookupResult;
import org.jboss.da.reports.backend.api.VersionFinder;
import org.jboss.da.rest.listings.model.SuccessResponse;
import org.jboss.da.rest.model.ErrorMessage;
import org.jboss.da.rest.reports.model.LookupReport;
import org.jboss.da.rest.reports.model.Report;
import org.slf4j.Logger;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;
import com.wordnik.swagger.annotations.ApiParam;
import com.wordnik.swagger.annotations.ApiResponse;
import com.wordnik.swagger.annotations.ApiResponses;

/**
 * Main end point for the reports
 * 
 * @author Dustin Kut Moy Cheung
 * @author Jakub Bartecek <jbartece@redhat.com>
 *
 */
@Path("/reports")
@Api(value = "/reports", description = "Get report of dependencies of projects")
public class Reports {

    @Inject
    private Logger log;

    @Inject
    private VersionFinder versionFinder;

    @Inject
    private ReportsGenerator reportsGenerator;

    @Inject
    private WhiteArtifactService whiteArtifactService;

    @Inject
    private BlackArtifactService blackArtifactService;

    @Inject
    private AproxConnector aprox;

    @POST
    @Path("/scm")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Get dependency report for a project specified in a repository URL",
            response = ArtifactReport.class)
    public Response scmGenerator(@ApiParam(value = "scm information") SCMLocator scm)
            throws Exception {

        try {

            Optional<ArtifactReport> artifactReport = reportsGenerator.getReportFromSCM(scm);

            return artifactReport
                    .map(x -> Response.ok().entity(artifactReport.get()).build())
                    .orElseGet(() -> Response.status(Status.NOT_FOUND)
                            .entity(new ErrorMessage("No relationship found")).build());

        } catch (ScmException|PomAnalysisException|IllegalArgumentException|CommunicationException e) {
            log.error("Exception thrown in scm endpoint", e);
            return Response.status(Status.INTERNAL_SERVER_ERROR).entity(e).build();
        }
    }

    @POST
    @Path("/gav")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Get dependency report for a GAV ",
            response = ArtifactReport.class)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Report was successfully generated"),
            @ApiResponse(code = 404, message = "Requested GA was not found"),
            @ApiResponse(code = 502, message = "Communication with remote repository failed") })
    public Response gavGenerator(@ApiParam(
            value = "JSON Object with keys 'groupId', 'artifactId', and 'version'") GAV gavRequest) {
        try {
            Optional<ArtifactReport> artifactReport = reportsGenerator.getReport(gavRequest);
            return artifactReport
                    .map(x -> Response.ok().entity(toReport(x)).build())
                    .orElseGet(() -> Response.status(Status.NOT_FOUND)
                            .entity(new ErrorMessage("Requested GA was not found")).build());
        } catch (CommunicationException ex) {
            log.error("Communication with remote repository failed", ex);
            return Response.status(502).entity(ex).build();
        }
    }

    @POST
    @Path("/lookup/gavs")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Lookup built versions for the list of provided GAVs",
            responseContainer = "List", response = LookupReport.class)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Lookup report was successfully generated"),
            @ApiResponse(code = 502, message = "Communication with remote repository failed") })
    public Response lookupGav(
            @ApiParam(
                    value = "JSON list of objects with keys 'groupId', 'artifactId', and 'version'") List<GAV> gavRequest) {
        List<LookupReport> reportsList = new ArrayList<>();

        boolean communicationSucceded = gavRequest.parallelStream().map((gav) -> {
            try {
                VersionLookupResult lookupResult = versionFinder.lookupBuiltVersions(gav);
                LookupReport lookupReport = toLookupReport(gav, lookupResult);
                reportsList.add(lookupReport);
                return true;
            } catch (CommunicationException ex) {
                log.error("Communication with remote repository failed", ex);
                return false;
            }
        }).allMatch(x -> {
            return x;
        });

        if (!communicationSucceded)
            return Response.status(502)
                    .entity(new ErrorMessage("Communication with remote repository failed"))
                    .build();
        else
            return Response.status(Status.OK).entity(reportsList).build();
    }

    @POST
    @Path("/repository")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Add repository to group for searching ",
            response = SuccessResponse.class)
    @ApiResponses(value = {
            @ApiResponse(code = 200,
                    message = "Repository successfully added to group for searching"),
            @ApiResponse(code = 502, message = "Communication with remote repository failed"),
            @ApiResponse(code = 409, message = "Repository with this name already exists") })
    public Response addRepository(@ApiParam(value = "repository data") Repository repository) {
        SuccessResponse response = new SuccessResponse();
        RepositoryManipulationStatus status;
        try {
            status = aprox.addRepositoryToGroup(repository);
        } catch (CommunicationException ex) {
            log.error("Communication with remote repository failed", ex);
            return Response.status(502)
                    .entity(new ErrorMessage("Communication with remote repository failed"))
                    .build();
        }
        switch (status) {
            case DONE:
                response.setSuccess(true);
                return Response.status(Status.OK).entity(response).build();
            case NAME_EXIST:
                response.setSuccess(false);
                response.setMessage("Repository with this name already exists");
                return Response.status(Status.CONFLICT).entity(response).build();
            default:
                response.setSuccess(false);
                return Response.status(Status.INTERNAL_SERVER_ERROR).entity(response).build();
        }
    }

    @DELETE
    @Path("/repository")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Remove repository from group for searching ",
            response = SuccessResponse.class)
    @ApiResponses(value = {
            @ApiResponse(code = 200,
                    message = "Repository successfully removed from group for searching"),
            @ApiResponse(code = 502, message = "Communication with remote repository failed"),
            @ApiResponse(code = 409,
                    message = "Repository with this name has different url than entered url"),
            @ApiResponse(code = 404, message = "Repository with this name was not found") })
    public Response removeRepository(Repository repository) {
        SuccessResponse response = new SuccessResponse();
        RepositoryManipulationStatus status;
        try {
            status = aprox.removeRepositoryFromGroup(repository);
        } catch (CommunicationException ex) {
            log.error("Communication with remote repository failed", ex);
            return Response.status(502)
                    .entity(new ErrorMessage("Communication with remote repository failed"))
                    .build();
        }
        switch (status) {
            case DONE:
                response.setSuccess(true);
                return Response.status(Status.OK).entity(response).build();
            case NAME_EXIST_DIFFERENT_URL:
                response.setSuccess(false);
                response.setMessage("Repository with this name has different url than entered url");
                return Response.status(Status.CONFLICT).entity(response).build();
            case NAME_NOT_EXIST:
                response.setSuccess(false);
                response.setMessage("Repository with this name was not found");
                return Response.status(Status.NOT_FOUND).entity(response).build();
            default:
                response.setSuccess(false);
                return Response.status(Status.INTERNAL_SERVER_ERROR).entity(response).build();
        }
    }

    @GET
    @Path("/repository")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Get all repositories from group for searching ",
            responseContainer = "List", response = Repository.class)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Repositories successfully returned"),
            @ApiResponse(code = 502, message = "Communication with remote repository failed") })
    public Response getAllRepositories() throws CommunicationException {
        List<Repository> repositories = new ArrayList<Repository>();
        repositories = aprox.getAllRepositoriesFromGroup();
        return Response.status(Status.OK).entity(repositories).build();
    }

    private static Report toReport(ArtifactReport report) {
        List<Report> dependencies = report.getDependencies()
                .stream()
                .map(Reports::toReport)
                .collect(Collectors.toList());

        return new Report(report.getGav(), new ArrayList<>(report.getAvailableVersions()),
                report.getBestMatchVersion().orElse(null), report.isDependencyVersionSatisfied(),
                dependencies,
                report.isBlacklisted(), report.isWhiteListed(), report.getNotBuiltDependencies());
    }

    private LookupReport toLookupReport(GAV gav, VersionLookupResult lookupResult) {
        return new LookupReport(gav, lookupResult.getBestMatchVersion().orElse(null),
                lookupResult.getAvailableVersions(), isBlacklisted(gav), isWhitelisted(gav));
    }

    private boolean isBlacklisted(GAV gav) {
        return blackArtifactService.isArtifactPresent(gav.getGroupId(), gav.getArtifactId(),
                gav.getVersion());
    }

    private boolean isWhitelisted(GAV gav) {
        return whiteArtifactService.isArtifactPresent(gav.getGroupId(), gav.getArtifactId(),
                gav.getVersion());
    }

}
