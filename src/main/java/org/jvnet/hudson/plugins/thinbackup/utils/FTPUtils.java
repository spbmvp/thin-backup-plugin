package org.jvnet.hudson.plugins.thinbackup.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.logging.Logger;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.jvnet.hudson.plugins.thinbackup.ThinBackupPluginImpl;
import org.jvnet.hudson.plugins.thinbackup.backup.BackupSet;

public class FTPUtils {
    private static final Logger LOGGER = Logger.getLogger("hudson.plugins.thinbackup");
    private String ftpServer;
    private String ftpLogin;
    private String ftpPassword;
    private FTPClient ftpClient;

    public FTPUtils(String ftpServer, String ftpLogin, String ftpPassword) {
        this.ftpServer = ftpServer;
        this.ftpLogin = ftpLogin;
        this.ftpPassword = ftpPassword;
    }

    public FTPClient createFtpClient(String ftpServer, String ftpLogin, String ftpPassword) {
        LOGGER.info("FTP start connect");
        int port = 21;
        String server = ftpServer;
        if (ftpServer.split(":| +", 2).length == 2) {
            server = ftpServer.split(":| +", 2)[0];
            port = Integer.parseInt(ftpServer.split(":| +", 2)[1]);
        }
        FTPClient ftpClient = new FTPClient();
        try {
            LOGGER.fine("FTP start connect");
            ftpClient.connect(server, port);
            int replyCode = ftpClient.getReplyCode();
            if (!FTPReply.isPositiveCompletion(replyCode)) {
                LOGGER.severe("Operation failed. Server reply code: " + replyCode);
                throw new IllegalArgumentException("Operation failed. Server reply code: " + replyCode);
            }
            LOGGER.fine("FTP login");
            boolean success = ftpClient.login(ftpLogin, ftpPassword);
            ftpClient.enterLocalPassiveMode();
            if (!success) {
                LOGGER.severe("Could not login to the server");
                throw new IllegalArgumentException("Could not login to the server");
            }
        } catch (IOException|IllegalArgumentException ex) {
            LOGGER.severe("Oops! Something wrong happened");
            ex.printStackTrace();
        }
        return ftpClient;
    }

    public void removeSuperfluousBackupSets(Integer nrMaxStoredFull, File expandedBackupPath, String ftpBackupPath) throws IOException {
        if (nrMaxStoredFull > 0) {
            ftpClient = createFtpClient(ftpServer, ftpLogin, ftpPassword);
            LOGGER.fine("Removing superfluous backup sets...");
            final List<BackupSet> validBackupSets = Utils.getValidBackupSets(expandedBackupPath);
            int nrOfRemovedBackups = 0;
            while (validBackupSets.size() > nrMaxStoredFull) {
                final BackupSet set = validBackupSets.get(0);
                removeDirectory(ftpBackupPath, set.getFullBackupName());
                validBackupSets.remove(set);
                ++nrOfRemovedBackups;
            }
            LOGGER.fine(String.format("DONE. Removed %d superfluous backup sets.", nrOfRemovedBackups));
            LOGGER.info("FTP logout. Removed superfluous backup DONE");
            ftpClient.logout();
            ftpClient.disconnect();
        }
    }

    public void ftpUploadBackup(String ftpBackupPath, File backupDirectory) {
        ftpClient = createFtpClient(ftpServer, ftpLogin, ftpPassword);
        LOGGER.info("FTP upload backup start");
        try {
            // Creates a directory
            String dirToCreate = ftpBackupPath + "/" + backupDirectory.getName();
            boolean success = ftpClient.changeWorkingDirectory(dirToCreate);
            if (!success) {
                LOGGER.info("FTP make dir " + dirToCreate);
                success = ftpClient.makeDirectory(dirToCreate);
                if (success) {
                    LOGGER.fine("Successfully created directory on FTP server: " + dirToCreate);
                } else {
                    LOGGER.severe("Failed to create directory. See server's reply.");
                }
            } else {
                LOGGER.fine("Directory on FTP server: '" + dirToCreate + "' exist");
            }
            // copy backup
            uploadDirectory(dirToCreate, backupDirectory.getPath(), "");
            // logs out
            LOGGER.info("FTP logout. Backup upload DONE");
            ftpClient.logout();
            ftpClient.disconnect();
        } catch (IOException ex) {
            LOGGER.severe("Oops! Something wrong happened");
            ex.printStackTrace();
        }
    }

    /**
     * Upload a whole directory (including its nested sub directories and files)
     * to a FTP server.
     *
     * @param remoteDirPath   Path of the destination directory on the server.
     * @param localParentDir  Path of the local directory being uploaded.
     * @param remoteParentDir Path of the parent directory of the current directory on the
     *                        server (used by recursive calls).
     * @throws IOException if any network or IO error occurred.
     */
    private void uploadDirectory(String remoteDirPath, String localParentDir, String remoteParentDir) throws IOException {

        LOGGER.fine("LISTING directory: " + localParentDir);

        File localDir = new File(localParentDir);
        File[] subFiles = localDir.listFiles();
        if (subFiles != null && subFiles.length > 0) {
            for (File item : subFiles) {
                String remoteFilePath = remoteDirPath + "/" + remoteParentDir
                        + "/" + item.getName();
                if (remoteParentDir.equals("")) {
                    remoteFilePath = remoteDirPath + "/" + item.getName();
                }


                if (item.isFile()) {
                    // upload the file
                    String localFilePath = item.getAbsolutePath();
                    LOGGER.fine("About to upload the file: " + localFilePath);
                    boolean uploaded = uploadSingleFile(localFilePath, remoteFilePath);
                    if (uploaded) {
                        LOGGER.fine("UPLOADED a file to: "
                                + remoteFilePath);
                    } else {
                        LOGGER.severe("COULD NOT upload the file: "
                                + localFilePath);
                    }
                } else {
                    // create directory on the server
                    boolean created = ftpClient.makeDirectory(remoteFilePath);
                    if (created) {
                        LOGGER.fine("CREATED the directory: "
                                + remoteFilePath);
                    } else {
                        LOGGER.severe("COULD NOT create the directory: "
                                + remoteFilePath);
                    }

                    // upload the sub directory
                    String parent = remoteParentDir + "/" + item.getName();
                    if (remoteParentDir.equals("")) {
                        parent = item.getName();
                    }

                    localParentDir = item.getAbsolutePath();
                    uploadDirectory(remoteDirPath, localParentDir, parent);
                }
            }
        }
    }

    /**
     * Upload a single file to the FTP server.
     *
     * @param localFilePath  Path of the file on local computer
     * @param remoteFilePath Path of the file on remote the server
     * @return true if the file was uploaded successfully, false otherwise
     * @throws IOException if any network or IO error occurred.
     */
    private boolean uploadSingleFile(String localFilePath, String remoteFilePath) throws IOException {
        File localFile = new File(localFilePath);

        try (InputStream inputStream = new FileInputStream(localFile)) {
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            return ftpClient.storeFile(remoteFilePath, inputStream);
        }
    }

    /**
     * Removes a non-empty directory by delete all its sub files and
     * sub directories recursively. And finally remove the directory.
     */
    private void removeDirectory(String parentDir, String currentDir) throws IOException {
        String dirToList = parentDir;
        if (!currentDir.equals("")) {
            dirToList += "/" + currentDir;
        }

        FTPFile[] subFiles = ftpClient.listFiles(dirToList);

        if (subFiles != null && subFiles.length > 0) {
            for (FTPFile aFile : subFiles) {
                String currentFileName = aFile.getName();
                if (currentFileName.equals(".") || currentFileName.equals("..")) {
                    // skip parent directory and the directory itself
                    continue;
                }
                String filePath = parentDir + "/" + currentDir + "/"
                        + currentFileName;
                if (currentDir.equals("")) {
                    filePath = parentDir + "/" + currentFileName;
                }

                if (aFile.isDirectory()) {
                    // remove the sub directory
                    removeDirectory(dirToList, currentFileName);
                } else {
                    // delete the file
                    boolean deleted = ftpClient.deleteFile(filePath);
                    if (deleted) {
                        LOGGER.fine("DELETED the file: " + filePath);
                    } else {
                        LOGGER.severe("CANNOT delete the file: "
                                + filePath);
                    }
                }
            }

            // finally, remove the directory itself
            boolean removed = ftpClient.removeDirectory(dirToList);
            if (removed) {
                LOGGER.fine("REMOVED the directory: " + dirToList);
            } else {
                LOGGER.severe("CANNOT remove the directory: " + dirToList);
            }
        }
    }
}
