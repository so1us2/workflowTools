package com.vmware.scm;

import com.vmware.scm.diff.PendingChangelistToGitDiffCreator;
import com.vmware.util.MatcherUtils;
import com.vmware.util.StringUtils;
import com.vmware.util.logging.LogLevel;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.vmware.scm.FileChangeType.deletedAfterRename;
import static com.vmware.scm.FileChangeType.renamed;
import static java.lang.String.format;

/**
 * Wrapper around p4 commands
 */
public class Perforce extends BaseScmWrapper {

    private static final Pattern whereDepotFileInfoPattern = Pattern.compile("(//.+?)\\s+");
    private static final Pattern whereLocalFileInfoPattern = Pattern.compile(".+?\\s+.+?\\s+(.+)");

    private String clientName;

    public Perforce(String clientName) {
        super(ScmType.perforce);
        this.clientName = clientName;
        if (StringUtils.isBlank(clientName)) {
            throw new IllegalArgumentException("perforceClientName config value is not set, can also be set by git-p4.client git config value");
        }
        File workingDirectory = getClientDirectory();
        if (workingDirectory == null) {
            throw new IllegalArgumentException("Failed to find root directory for perforce client " + clientName);
        }
        super.setWorkingDirectory(workingDirectory);
    }

    public List<String> getPendingChangelists() {
        String changeLists = executeScmCommand("p4 changes -c {} -s pending", clientName);
        if (StringUtils.isBlank(changeLists)) {
            return Collections.emptyList();
        }
        List<String> changeListIds = new ArrayList<>();
        for (String line : changeLists.split("\n")) {
            changeListIds.add(MatcherUtils.singleMatch(line, "Change\\s+(\\d+)\\s+on"));
        }
        return changeListIds;
    }

    public String readLastPendingChangelist() {
        String output = executeScmCommand("p4 changes -m 1 -s pending -l -c {}", clientName);
        output = output.replaceAll("\n\t", "\n");
        return output;
    }

    public String readChangelist(String changelistId) {
        String output = executeScmCommand("p4 describe -s {}", changelistId);
        output = output.replaceAll("\n\t", "\n");
        return output;
    }

    public String printToFile(String fileToPrint, File outputFile) {
        return executeScmCommand("p4 print -o {} {}", outputFile.getPath(), fileToPrint);
    }

    public void deletePendingChangelist(String changelistId) {
        executeScmCommand("p4 change -d " + changelistId, LogLevel.INFO);
    }

    public void revertChangesInPendingChangelist(String changelistId) {
        executeScmCommand("p4 revert -w -c {} //...", LogLevel.INFO, changelistId);
    }

    public void revertFile(String changelistId, String file) {
        executeScmCommand("p4 revert -w -c {} {}", changelistId, file);
    }

    public String createPendingChangelist(String description, boolean filesExpected) {
        String perforceTemplate = executeScmCommand("p4 change -o");
        String amendedTemplate = updateTemplateWithDescription(perforceTemplate, description, filesExpected);
        String output = executeScmCommand("p4 change -i", amendedTemplate, LogLevel.DEBUG);
        return changeSucceeded(output) ? MatcherUtils.singleMatch(output, "Change\\s+(\\d+)\\s+created") : null;
    }

    public void clean() {
        executeScmCommand("p4 clean //...", LogLevel.INFO);
    }

    public void moveAllOpenFilesToChangelist(String changelistId) {
        executeScmCommand("p4 reopen -c " + changelistId + " //...", LogLevel.INFO);
    }

    public String getFileInfo(String filePath) {
        return executeScmCommand("p4 files " + filePath);
    }

    public Map<String, String> getWhereDepotFileInfoForRelativePaths(List<String> filePaths) {
        String fullPathPrefix = getWorkingDirectory().getPath() + File.separator;
        String filePathTexts = fullPathPrefix + StringUtils.appendWithDelimiter("", filePaths, " " + fullPathPrefix);
        String[] whereFileOutput = executeScmCommand("p4 where " + filePathTexts).split("\n");
        return addMatchedValuesToMap(filePaths, Arrays.asList(whereFileOutput), whereDepotFileInfoPattern);
    }

    public Map<String, String> getWhereLocalFileInfo(List<String> filePaths) {
        String filePathTexts = StringUtils.appendWithDelimiter("", filePaths, " ");
        String[] whereFileOutput = executeScmCommand("p4 where " + filePathTexts).split("\n");
        return addMatchedValuesToMap(filePaths, Arrays.asList(whereFileOutput), whereLocalFileInfoPattern);
    }

    public String getCurrentChangelistId(String id) {
        String changelistText = executeScmCommand("p4 change -o -O " + id);
        return MatcherUtils.singleMatch(changelistText, "Change:\\s+(\\d+)");
    }

    public String getChangelistStatus(String id) {
        String changelistText = executeScmCommand("p4 change -o -O " + id);
        return MatcherUtils.singleMatch(changelistText, "Status:\\s+(pending|submitted)");
    }

    public List<FileChange> getFileChangesForPendingChangelist(String id) {
        String filesText = executeScmCommand("p4 -ztag opened -c {}", id);
        Matcher lineMatcher = Pattern.compile("\\.\\.\\.\\s+(\\w+)\\s+(.+)").matcher(filesText);
        FileChange fileChange = null;
        List<FileChange> fileChanges = new ArrayList<>();
        String clientNameToStrip = "//" + clientName + File.separator;
        while (lineMatcher.find()) {
            String valueName = lineMatcher.group(1);
            String value = lineMatcher.group(2);
            if (valueName.equals("depotFile")) {
                if (fileChange != null) {
                    fileChanges.add(fileChange);
                }
                fileChange = new FileChange(scmType);
            }
            if (fileChange != null) {
                fileChange.parseValue(valueName, value, clientNameToStrip);
            }
        }
        if (fileChange != null) {
            fileChanges.add(fileChange);
        }
        mergeMoveDeleteAndAdds(fileChanges);

        return fileChanges;
    }

    public String diffChangelistInGitFormat(String changelistId, boolean binaryPatch) {
        List<FileChange> fileChanges = getFileChangesForPendingChangelist(changelistId);
        return diffChangelistInGitFormat(fileChanges, changelistId, binaryPatch);
    }

    public String diffChangelistInGitFormat(List<FileChange> fileChanges, String changelistId, boolean binaryPatch) {
        String filesToDiff = "";
        if (fileChanges.isEmpty()) {
            log.warn("No open file changes for changelist {}", changelistId);
            return "";
        }
        for (FileChange fileChange : fileChanges) {
            if (!filesToDiff.isEmpty()) {
                filesToDiff += " ";
            }
            filesToDiff += fullPath(fileChange.getLastFileAffected());
        }
        String diffData = diffFilesUsingGit(filesToDiff, binaryPatch);
        PendingChangelistToGitDiffCreator diffCreator = new PendingChangelistToGitDiffCreator(this);
        return diffCreator.create(diffData, fileChanges, binaryPatch);
    }

    private String diffFilesUsingGit(String filesToDiff, boolean binaryPatch) {
        Map<String, String> environmentVariables = new HashMap<>();
        String binaryFlag = binaryPatch ? " --binary" : "";
        environmentVariables.put("P4DIFF", "git diff --full-index" + binaryFlag);
        return executeScmCommand(environmentVariables, "p4 diff -du", filesToDiff, LogLevel.DEBUG);
    }

    private void mergeMoveDeleteAndAdds(List<FileChange> fileChanges) {
        for (FileChange fileChange : fileChanges) {
            if (fileChange.getChangeType() != renamed) {
                continue;
            }
            String moveDepotFile = fileChange.getFirstFileAffected();
            boolean foundMatchingDeleteFile = false;
            for (FileChange matchingDeleteChange : fileChanges) {
                if (matchingDeleteChange.getChangeType() == deletedAfterRename
                        && moveDepotFile.equals(matchingDeleteChange.getDepotFile())) {
                    foundMatchingDeleteFile = true;
                    String deleteClientFile = matchingDeleteChange.getLastFileAffected();
                    fileChange.replaceFileAffected(0, deleteClientFile);
                    break;
                }
            }
            if (!foundMatchingDeleteFile) {
                throw new RuntimeException("Expected to find matching move/delete action for move depot file " + moveDepotFile);
            }
        }

        Iterator<FileChange> changeIterator = fileChanges.iterator();
        while (changeIterator.hasNext()) {
            FileChange fileChange = changeIterator.next();
            if (fileChange.getChangeType() == FileChangeType.deletedAfterRename) {
                changeIterator.remove();
            }
        }
    }

    public String sync(String fileNames) {
        return executeScmCommand("p4 sync {}", fileNames);
    }

    public String move(String changelistId, String fromFileName, String toFileName, String extraFlags) {
        String output = executeScmCommand("p4 move {} -c {} {} {}", extraFlags, changelistId, fromFileName, toFileName);
        return failOutputIfMissingText(output, "moved from");
    }

    public String add(String changelistId, String fileName) {
        String output = executeScmCommand("p4 add -c {} {}", changelistId, fileName);
        return failOutputIfMissingText(output, "opened for add");
    }

    public String openForEdit(String changelistId, String fileName) {
        String output = executeScmCommand("p4 edit -c {} {}", changelistId, fileName);
        return failOutputIfMissingText(output, "opened for edit");
    }

    public String markForDelete(String changelistId, String fileName) {
        String output = executeScmCommand("p4 delete -c {} {}", changelistId, fileName);
        return failOutputIfMissingText(output, "opened for delete");
    }

    public boolean updatePendingChangelist(String id, String description) {
        String perforceTemplate = executeScmCommand("p4 change -o " + id);
        String amendedTemplate = updateTemplateWithDescription(perforceTemplate, description, false);
        String output = executeScmCommand("p4 change -i", amendedTemplate, LogLevel.DEBUG);
        return changeSucceeded(output);
    }

    public void submitChangelist(String id, String description) {
        String perforceTemplate = executeScmCommand("p4 change -o " + id);
        String amendedTemplate = updateTemplateWithDescription(perforceTemplate, description, true);
        String submitOutput = executeScmCommand("p4 submit -f revertunchanged -i", amendedTemplate, LogLevel.INFO);
        String status = getChangelistStatus(id);
        if (!"submitted".equals(status)) {
            log.error("Changelist {} has status {}, expected submitted", id, status);
            log.error("Submit output\n{}", submitOutput);
            System.exit(1);
        }
    }

    private boolean changeSucceeded(String output) {
        if (output.contains("Error in change specification")) {
            log.error("Failed to apply change\n{}\n", output);
            return false;
        } else {
            return true;
        }
    }

    private File getClientDirectory() {
        String info = executeScmCommand("p4 clients -e " + clientName, LogLevel.DEBUG);
        String clientDirectory = MatcherUtils.singleMatch(info, "Client\\s+" + clientName + "\\s+.+?(\\S+)\\s+'Created by");
        if (clientDirectory != null) {
            return new File(clientDirectory);
        } else {
            log.warn("{} not found in clients list\n{}", clientName, info);
            return null;
        }
    }

    private String updateTemplateWithDescription(String perforceTemplate, String commitText, boolean filesExpected) {
        int descriptionIndex = perforceTemplate.indexOf("Description:");
        if (descriptionIndex == -1) {
            throw new IllegalArgumentException("Failed to find Description: in perforce template:\n" + perforceTemplate);
        }
        int filesIndex = perforceTemplate.indexOf("Files:");
        if (filesIndex == -1 && filesExpected) {
            throw new IllegalArgumentException("Failed to find Files: in perforce template, does the git commit have file changes?:\n" + perforceTemplate);
        } else if (filesIndex == -1) {
            log.debug("No files detected");
            filesIndex = perforceTemplate.length();
        }

        String amendedCommitText = "\t" + commitText.replaceAll("\n", "\n\t");

        return format("%sDescription:\n%s\n%s",
                perforceTemplate.substring(0, descriptionIndex), amendedCommitText, perforceTemplate.substring(filesIndex));
    }

    private Map<String, String> addMatchedValuesToMap(List<String> sourceValues, List<String> outputTexts, Pattern pattern) {
        Matcher matcher = pattern.matcher("");
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < sourceValues.size(); i ++) {
            matcher.reset(outputTexts.get(i));
            if (!matcher.find()) {
                throw new RuntimeException("Failed to match pattern "
                        + matcher.pattern().pattern() + " in text " + outputTexts.get(i));
            }
            values.put(sourceValues.get(i), matcher.group(1));
        }
        return values;

    }

}