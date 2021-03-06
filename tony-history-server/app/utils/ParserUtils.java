package utils;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import models.JobConfig;
import models.JobMetadata;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import play.Logger;

import static utils.HdfsUtils.*;


/**
 * The class handles parsing different file format
 */
public class ParserUtils {
  private static final Logger.ALogger LOG = Logger.of(ParserUtils.class);

  /**
   * Checks if {@code fileName} string contains valid metadata.
   * Ex: If {@code jobIdRegex} is "^application_\\d+_\\d+$",
   * metadata = "application_1541469337545_0031-1542325695566-1542325733637-user1-FAILED" -> true
   * metadata = "application_1541469337545_0031-1542325695566-1542325733637-user2-succeeded" -> false
   * because status should be upper-cased.
   * metadata = "job_01_01-1542325695566-1542325733637-user3-SUCCEEDED" -> false
   * because the job ID portion doesn't match {@code jobIdRegex}.
   * If a metadata component doesn't satisfy its corresponding condition, {@code fileName} is invalid.
   * @param fileName A string with metadata components.
   * @param jobIdRegex Regular expression string to validate metadata.
   * @return true if {@code fileName} are in proper format, false otherwise.
   */
  @VisibleForTesting
  static boolean isValidHistFileName(String fileName, String jobIdRegex) {
    String histFileNoExt = fileName.substring(0, fileName.lastIndexOf('.'));
    String[] metadataArr = histFileNoExt.split("-");
    if (metadataArr.length != 5) {
      LOG.error("Missing fields in metadata");
      return false;
    }
    return metadataArr[0].matches(jobIdRegex)
        && metadataArr[1].matches("\\d+")
        && metadataArr[2].matches("\\d+")
        && metadataArr[3].matches(metadataArr[3].toLowerCase())
        && metadataArr[4].equals(metadataArr[4].toUpperCase());
  }

  /**
   * Assuming that there's only 1 jhist file in {@code jobFolderPath},
   * this function parses metadata and return a {@code JobMetadata} object.
   * @param fs FileSystem object.
   * @param jobFolderPath Path object of job directory.
   * @param jobIdRegex Regular expression string to validate metadata.
   * @return a {@code JobMetadata} object.
   */
  public static JobMetadata parseMetadata(FileSystem fs, Path jobFolderPath, String jobIdRegex) {
    String[] jobFilesArr;
    if (!pathExists(fs, jobFolderPath)) {
      return null;
    }

    try {
      jobFilesArr = Arrays.stream(fs.listStatus(jobFolderPath))
          .filter(f -> f.getPath().toString().endsWith("jhist"))
          .map(f -> f.getPath().toString())
          .toArray(String[]::new);
      Preconditions.checkArgument(jobFilesArr.length == 1);
    } catch (IOException e) {
      LOG.error("Failed to scan " + jobFolderPath.toString(), e);
      return null;
    }

    String histFileName = HdfsUtils.getJobId(jobFilesArr[0]);
    if (!isValidHistFileName(histFileName, jobIdRegex)) {
      // this should never happen unless user rename the history file
      LOG.error("Metadata isn't valid");
      return null;
    }

    LOG.debug("Successfully parsed metadata");
    return JobMetadata.newInstance(histFileName);
  }

  /**
   * Assuming that there's only 1 config.xml file in {@code jobFolderPath},
   * this function parses config.xml and return a list of {@code JobConfig} objects.
   * @param fs FileSystem object.
   * @param jobFolderPath Path object of job directory.
   * @return a list of {@code JobConfig} objects.
   */
  public static List<JobConfig> parseConfig(FileSystem fs, Path jobFolderPath) {
    if (!pathExists(fs, jobFolderPath)) {
      return Collections.emptyList();
    }

    Path configFilePath = new Path(jobFolderPath, "config.xml");
    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    List<JobConfig> configs = new ArrayList<>();

    try (FSDataInputStream inStrm = fs.open(configFilePath)) {
      DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
      Document doc = dBuilder.parse(inStrm);
      doc.getDocumentElement().normalize();

      NodeList properties = doc.getElementsByTagName("property");

      for (int i = 0; i < properties.getLength(); i++) {
        Node property = properties.item(i);
        if (property.getNodeType() == Node.ELEMENT_NODE) {
          Element p = (Element) property;
          JobConfig jobConf = new JobConfig();
          jobConf.setName(p.getElementsByTagName("name").item(0).getTextContent());
          jobConf.setValue(p.getElementsByTagName("value").item(0).getTextContent());
          jobConf.setFinal(p.getElementsByTagName("final").item(0).getTextContent().equals("true"));
          jobConf.setSource(p.getElementsByTagName("source").item(0).getTextContent());
          configs.add(jobConf);
        }
      }
    } catch (SAXException e) {
      LOG.error("Failed to parse config file", e);
      return Collections.emptyList();
    } catch (ParserConfigurationException e) {
      LOG.error("Failed to init XML parser", e);
      return Collections.emptyList();
    } catch (IOException e) {
      LOG.error("Failed to read config file", e);
      return Collections.emptyList();
    }
    LOG.debug("Successfully parsed config");
    return configs;
  }

  private ParserUtils() { }
}
