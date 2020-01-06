package com.vmware.action.vcd;

import com.vmware.config.ActionDescription;
import com.vmware.config.WorkflowConfig;

@ActionDescription("Select Vapp if needed for json parameter.")
public class SelectVappIfNeeded extends SelectVapp {
    public SelectVappIfNeeded(WorkflowConfig config) {
        super(config);
    }

    @Override
    public String cannotRunAction() {
        if (!jenkinsConfig.useVappJsonParameter) {
            return "useVappJsonParameter is set to false";
        }
        return super.cannotRunAction();
    }
}
