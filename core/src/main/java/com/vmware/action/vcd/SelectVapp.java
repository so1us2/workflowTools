package com.vmware.action.vcd;

import com.vmware.action.base.BaseVappAction;
import com.vmware.config.ActionDescription;
import com.vmware.config.WorkflowConfig;
import com.vmware.util.StringUtils;
import com.vmware.util.input.InputUtils;
import com.vmware.vcd.domain.QueryResultVappType;

@ActionDescription("Select a specific Vapp.")
public class SelectVapp extends BaseVappAction {
    public SelectVapp(WorkflowConfig config) {
        super(config);
    }

    @Override
    public void checkIfActionShouldBeSkipped() {
        super.checkIfActionShouldBeSkipped();
        super.skipActionIfTrue(sshConfig.usesSshSite(), "ssh site is configured");
    }

    @Override
    protected void failWorkflowIfConditionNotMet() {
        super.failWorkflowIfConditionNotMet();
        super.failIfTrue(vappData.getVapps().isEmpty() && StringUtils.isEmpty(vcdConfig.vappJsonFile) && !jenkinsConfig.hasConfiguredArtifact(),
                "no vapps loaded");
    }

    @Override
    public void process() {
        if (StringUtils.isNotEmpty(vcdConfig.vappJsonFile)) {
            log.info("Using Vapp json file {}", vcdConfig.vappJsonFile);
            vappData.setSelectedVapp(new QueryResultVappType("url", vcdConfig.vappJsonFile));
        } else if (jenkinsConfig.hasConfiguredArtifact()) {
            String jobArtifactPath = serviceLocator.getJenkins()
                    .constructFullArtifactPath(getJobForArtifact(), jenkinsConfig.jobBuildNumber, jenkinsConfig.jobArtifact);
            log.info("Using artifact {}", jobArtifactPath);
            vappData.setSelectedVapp(new QueryResultVappType("artifact", jobArtifactPath));
        } else if (StringUtils.isNotEmpty(vcdConfig.vappName)) {
            log.info("Using specified Vapp name {}", vcdConfig.vappName);
            vappData.setSelectedVappByName(vcdConfig.vappName);
        } else if (!vappData.noVappSelected()) {
            log.info("Using already selected Vapp {}", vappData.getSelectedVapp().getLabel());
        } else {
            int selectedVapp = InputUtils.readSelection(vappData.vappLabels(),
                    "Select Vapp (Total powered on owned VM count " + vappData.poweredOnVmCount() + ")");
            vappData.setSelectedVappByIndex(selectedVapp);
        }
    }

    public String getJobForArtifact() {
        if (StringUtils.isNotEmpty(jenkinsConfig.jobWithArtifact)) {
            return jenkinsConfig.jobWithArtifact;
        } else {
            return jenkinsConfig.jobsDisplayNames != null && jenkinsConfig.jobsDisplayNames.length == 1 ? jenkinsConfig.jobsDisplayNames[0] : null;
        }
    }
}
