package org.jboss.da.reports.impl;

import org.apache.maven.scm.ScmException;
import org.jboss.da.common.version.VersionParser;
import org.jboss.da.communication.aprox.FindGAVDependencyException;
import org.jboss.da.communication.aprox.model.GAVDependencyTree;
import org.jboss.da.common.CommunicationException;
import org.jboss.da.communication.model.GAV;
import org.jboss.da.communication.pom.PomAnalysisException;
import org.jboss.da.communication.pom.api.PomAnalyzer;
import org.jboss.da.listings.api.service.BlackArtifactService;
import org.jboss.da.listings.api.service.WhiteArtifactService;
import org.jboss.da.reports.api.AdvancedArtifactReport;
import org.jboss.da.reports.api.ArtifactReport;
import org.jboss.da.reports.api.Product;
import org.jboss.da.reports.api.ReportsGenerator;
import org.jboss.da.reports.api.SCMLocator;
import org.jboss.da.reports.api.VersionLookupResult;
import org.jboss.da.reports.backend.api.VersionFinder;
import org.jboss.da.scm.api.SCM;
import org.jboss.da.scm.api.SCMType;
import org.slf4j.Logger;

import javax.inject.Inject;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jboss.da.reports.backend.api.DependencyTreeGenerator;

/**
 * The implementation of reports, which provides information about
 * built/not built artifacts/blacklisted artifacts
 *
 * @author Jakub Bartecek <jbartece@redhat.com>
 * @author Honza Brázdil <jbrazdil@redhat.com>
 */
public class ReportsGeneratorImpl implements ReportsGenerator {

    @Inject
    private Logger log;

    @Inject
    private VersionFinder versionFinderImpl;

    @Inject
    private BlackArtifactService blackArtifactService;

    @Inject
    private WhiteArtifactService whiteArtifactService;

    @Inject
    private DependencyTreeGenerator dependencyTreeGenerator;

    @Inject
    private SCM scmManager;

    @Inject
    private PomAnalyzer pomAnalyzer;

    @Override
    public Optional<ArtifactReport> getReportFromSCM(SCMLocator scml) throws ScmException,
            PomAnalysisException, CommunicationException {
        if (scml == null)
            throw new IllegalArgumentException("SCM information can't be null");

        GAVDependencyTree dt = dependencyTreeGenerator.getDependencyTree(scml);

        VersionLookupResult result = versionFinderImpl.lookupBuiltVersions(dt.getGav());
        ArtifactReport report = toArtifactReport(dt.getGav(), result);

        Set<GAVDependencyTree> nodesVisited = new HashSet<>();
        nodesVisited.add(dt);
        addDependencyReports(report, dt.getDependencies(), nodesVisited);

        return Optional.of(report);
    }

    @Override
    public Optional<AdvancedArtifactReport> getAdvancedReportFromSCM(SCMLocator scml) throws ScmException,
            PomAnalysisException, CommunicationException {
        Optional<ArtifactReport> artifactReport = getReportFromSCM(scml);
        // TODO: hardcoded to git
        // hopefully we'll get the cached cloned folder for this repo
        File repoFolder = scmManager.cloneRepository(SCMType.GIT, scml.getScmUrl(), scml.getRevision());
        return artifactReport.map(r -> generateAdvancedArtifactReport(r, repoFolder));
    }

    @Override
    public Optional<ArtifactReport> getReport(GAV gav, List<Product> products) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ArtifactReport getReport(GAV gav) throws CommunicationException,
            FindGAVDependencyException {
        if (gav == null)
            throw new IllegalArgumentException("GAV can't be null");

        GAVDependencyTree dt = dependencyTreeGenerator.getDependencyTree(gav);

        Set<GAVDependencyTree> nodesVisited = new HashSet<>();
        VersionLookupResult result = versionFinderImpl.lookupBuiltVersions(gav);
        ArtifactReport report = toArtifactReport(gav, result);

        nodesVisited.add(dt);
        addDependencyReports(report, dt.getDependencies(), nodesVisited);

        return report;
    }

    private ArtifactReport toArtifactReport(GAV gav, VersionLookupResult result) {
        ArtifactReport report = new ArtifactReport(gav);
        report.addAvailableVersions(result.getAvailableVersions());
        report.setBestMatchVersion(result.getBestMatchVersion());
        report.setBlacklisted(blackArtifactService.isArtifactPresent(gav));
        report.setWhitelisted(whiteArtifactService.isArtifactPresent(gav));
        return report;
    }

    private void addDependencyReports(ArtifactReport ar, Set<GAVDependencyTree> dependencyTree,
            Set<GAVDependencyTree> nodesVisited) throws CommunicationException {
        for (GAVDependencyTree dt : dependencyTree) {

            VersionLookupResult result = versionFinderImpl.lookupBuiltVersions(dt.getGav());

            ArtifactReport dar = toArtifactReport(dt.getGav(), result);

            // if dt hasn't been visited yet, add dependencies of dt in the report
            if (!nodesVisited.contains(dt))
                addDependencyReports(dar, dt.getDependencies(), nodesVisited);

            ar.addDependency(dar);
            nodesVisited.add(dt);
        }
    }

    private AdvancedArtifactReport generateAdvancedArtifactReport(ArtifactReport report,
            File repoFolder) {
        AdvancedArtifactReport advancedReport = new AdvancedArtifactReport();
        advancedReport.setArtifactReport(report);
        Set<GAV> modulesAnalyzed = new HashSet<>();

        // hopefully we'll get the folder already cloned from before
        populateAdvancedArtifactReportFields(advancedReport, report, modulesAnalyzed, repoFolder);
        return advancedReport;
    }

    private void populateAdvancedArtifactReportFields(AdvancedArtifactReport advancedReport,
            ArtifactReport report, Set<GAV> modulesAnalyzed, File repoFolder) {
        for (ArtifactReport dep : report.getDependencies()) {
            if (modulesAnalyzed.contains(dep.getGav())) {
                // if module already analyzed, skip
                continue;
            } else if (isDependencyAModule(repoFolder, dep)) {
                // if dependency is a module, but not yet analyzed
                modulesAnalyzed.add(dep.getGav());
                populateAdvancedArtifactReportFields(advancedReport, dep, modulesAnalyzed,
                        repoFolder);
            } else {
                // only generate populate advanced report with community GAVs
                if (VersionParser.isRedhatVersion(dep.getVersion()))
                    continue;

                // we have a top-level module dependency
                if (dep.isWhitelisted())
                    advancedReport.addWhitelistArtifact(dep.getGav());
                if (dep.isBlacklisted())
                    advancedReport.addBlacklistArtifact(dep.getGav());

                if (dep.getBestMatchVersion().isPresent()) {
                    advancedReport.addCommunityGavWithBestMatchVersion(dep.getGav());
                } else if (!dep.getAvailableVersions().isEmpty()
                        || hasWhitelistedGA(dep.getGroupId(), dep.getArtifactId())) {
                    advancedReport.addCommunityGavWithBuiltVersion(dep.getGav());
                } else {
                    advancedReport.addCommunityGav(dep.getGav());
                }
            }
        }
    }

    private boolean hasWhitelistedGA(String groupId, String artifactId) {
        if (whiteArtifactService.getAll() == null) {
            return false;
        }
        return whiteArtifactService.getAll().stream()
                .anyMatch(white -> white.getArtifactId().equals(artifactId) && white.getGroupId().equals(groupId));
    }

    private boolean isDependencyAModule(File repoFolder, ArtifactReport dependency) {
        return pomAnalyzer.getPOMFileForGAV(repoFolder, dependency.getGav()).isPresent();
    }

}
