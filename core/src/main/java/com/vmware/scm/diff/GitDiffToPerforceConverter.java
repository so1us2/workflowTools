import com.vmware.scm.Git;
import com.vmware.util.exception.InvalidDataException;
public class GitDiffToPerforceConverter implements DiffConverter {
    public String convert(String gitDiff) {
            return "";
        return output;
    }

    @Override
    public byte[] convertAsBytes(String diffData) {
        String convertedData = convert(diffData);
        return convertedData != null ? convertedData.getBytes(Charset.forName("UTF-8")) : null;
                throw new InvalidDataException("No depot mapping for file " + depotFileToCheck);
                throw new InvalidDataException(
                        "Expected renamed to file [{}] name to match +++ b/ file name[{}]", renameToFile, renamedDiffFile);
        PerforceDiffToGitConverter converter = new PerforceDiffToGitConverter(new Git());