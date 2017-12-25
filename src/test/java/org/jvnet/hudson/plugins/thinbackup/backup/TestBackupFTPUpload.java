package org.jvnet.hudson.plugins.thinbackup.backup;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import hudson.model.ItemGroup;
import hudson.model.TopLevelItem;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.jvnet.hudson.plugins.thinbackup.TestHelper;
import org.jvnet.hudson.plugins.thinbackup.ThinBackupPeriodicWork;
import org.jvnet.hudson.plugins.thinbackup.ThinBackupPluginImpl;
import org.jvnet.hudson.plugins.thinbackup.utils.Utils;
import org.mockftpserver.fake.FakeFtpServer;
import org.mockftpserver.fake.UserAccount;
import org.mockftpserver.fake.filesystem.DirectoryEntry;
import org.mockftpserver.fake.filesystem.FileSystem;
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem;

public class TestBackupFTPUpload {

    private File backupDir;
    private File jenkinsHome;
    private FakeFtpServer fakeFtpServer;
    private FileSystem fileSystem;
    private File buildDir;
    private ItemGroup<TopLevelItem> mockHudson;

    private String USER = "user";
    private String PASSWORD = "password";

    @Before
    public void setup() throws IOException, InterruptedException {
        mockHudson = mock(ItemGroup.class);

        File base = new File(System.getProperty("java.io.tmpdir"));
        backupDir = TestHelper.createBackupFolder(base);
        fakeFtpServer = new FakeFtpServer();
        fakeFtpServer.addUserAccount(new UserAccount(USER, PASSWORD, backupDir.getPath()));
        fakeFtpServer.setServerControlPort(0);
        fileSystem = new UnixFakeFileSystem();
        fileSystem.add(new DirectoryEntry(backupDir.getPath()));
        fakeFtpServer.setFileSystem(fileSystem);
        fakeFtpServer.start();

        jenkinsHome = TestHelper.createBasicFolderStructure(base);
        File jobDir = TestHelper.createJob(jenkinsHome, TestHelper.TEST_JOB_NAME);
        buildDir = TestHelper.addNewBuildToJob(jobDir);
    }

    @After
    public void tearDown() throws Exception {
        FileUtils.deleteDirectory(jenkinsHome);
        FileUtils.deleteDirectory(backupDir);
        FileUtils.deleteDirectory(buildDir);
        FileUtils.deleteDirectory(new File(Utils.THINBACKUP_TMP_DIR));
        fakeFtpServer.stop();
    }

    @Test
    public void testBackup() throws Exception {
        final ThinBackupPluginImpl mockPlugin = TestHelper.createMockPlugin(jenkinsHome, backupDir);

        when(mockPlugin.getFtpServer()).thenReturn("localhost:" + fakeFtpServer.getServerControlPort());
        when(mockPlugin.getFtpLogin()).thenReturn(USER);
        when(mockPlugin.getFtpPassword()).thenReturn(PASSWORD);
        when(mockPlugin.getFtpBackupPath()).thenReturn(backupDir.getPath());

        new HudsonBackup(mockPlugin, ThinBackupPeriodicWork.BackupType.FULL, new Date(), mockHudson).backup();

        String[] list = backupDir.list();
        Assert.assertEquals(1, list.length);
        Assert.assertEquals(1, fileSystem.listFiles(backupDir.getPath()).size());
        final File backup = new File(backupDir, list[0]);
        list = backup.list();
        Assert.assertEquals(6, list.length);
        Assert.assertEquals(6, fileSystem.listFiles(backup.getPath()).size());

        final File job = new File(new File(backup, HudsonBackup.JOBS_DIR_NAME), TestHelper.TEST_JOB_NAME);
        final List<String> arrayList = Arrays.asList(job.list());
        Assert.assertEquals(2, arrayList.size());
        Assert.assertEquals(2, fileSystem.listFiles(job.getPath()).size());
        Assert.assertFalse(arrayList.contains(HudsonBackup.NEXT_BUILD_NUMBER_FILE_NAME));

        final File build = new File(new File(job, HudsonBackup.BUILDS_DIR_NAME), TestHelper.CONCRET_BUILD_DIRECTORY_NAME);
        list = build.list();
        Assert.assertEquals(7, list.length);
        Assert.assertEquals(7, fileSystem.listFiles(build.getPath()).size());

        final File changelogHistory = new File(
                new File(new File(job, HudsonBackup.BUILDS_DIR_NAME), TestHelper.CONCRET_BUILD_DIRECTORY_NAME),
                HudsonBackup.CHANGELOG_HISTORY_PLUGIN_DIR_NAME);
        list = changelogHistory.list();
        Assert.assertEquals(2, list.length);
        Assert.assertEquals(2, fileSystem.listFiles(changelogHistory.getPath()).size());
    }
}
