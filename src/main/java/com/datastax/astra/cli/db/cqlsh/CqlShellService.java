package com.datastax.astra.cli.db.cqlsh;

import com.datastax.astra.cli.core.CliContext;
import com.datastax.astra.cli.core.exception.CannotStartProcessException;
import com.datastax.astra.cli.core.exception.FileSystemException;
import com.datastax.astra.cli.core.out.LoggerShell;
import com.datastax.astra.cli.db.DatabaseDao;
import com.datastax.astra.cli.db.exception.SecureBundleNotFoundException;
import com.datastax.astra.cli.utils.AstraCliUtils;
import com.datastax.astra.cli.utils.FileUtils;
import com.datastax.astra.sdk.config.AstraClientConfig;
import com.datastax.astra.sdk.databases.domain.Database;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Working with external cqlsh.
 *
 * @author Cedrick LUNVEN (@clunven)
 */
public class CqlShellService {

    /** Patch for cqlShell. */
    private static final String VERSION_TO_REPLACE = "|| [ \"$version\" = \"2.7\" ]";

    /** Patch for cqlShell. */
    private static final String VERSION_REPLACED = "|| [ \"$version\" = \"3.10\" ] || [ \"$version\" \\> \"3.10\" ] || [ \"$version\" = \"2.7\" ]";

    /** Configuration to Download the archive. */
    CqlShellConfig settings;

    /** Access to Database client */
    DatabaseDao dbDao;
    
    /** Local installation folder for CqlSh. */
    File cqlshLocalFolder;

    /** Local installation for CqlSh. */
    String cqlshExecutable;

    /** Singleton Pattern. */
    private static CqlShellService instance;

    /**
     * Singleton Pattern.
     * 
     * @return
     *      instance of the service.
     */
    public static synchronized CqlShellService getInstance() {
        if (null == instance) {
            instance = new CqlShellService();
        }
        return instance;
    }
    
    /**
     * Default constructor
     */
    private CqlShellService() {
        this.dbDao = DatabaseDao.getInstance();
        
        settings = new CqlShellConfig(
                AstraCliUtils.readProperty("cqlsh.url"),
                AstraCliUtils.readProperty("cqlsh.tarball"));
                
        cqlshLocalFolder = new File(AstraCliUtils.ASTRA_HOME + File.separator + "cqlsh-astra");

        cqlshExecutable = cqlshLocalFolder.getAbsolutePath() + File.separator + "bin" + File.separator + "cqlsh";
    }
    
    /**
     * Check if cqlshell has been installed.
     *
     * @return
     *      if the folder exist
     */
    public boolean isInstalled() {
       return cqlshLocalFolder.exists() && cqlshLocalFolder.isDirectory();
    }

    /**
     * Waiting for a fix at Cqlsh-Astra system
     */
    public void patchCqlshInstallation() {
        String workingDirectory = cqlshLocalFolder.getAbsolutePath() + File.separator + "bin" + File.separator;
        File originalCqlsh = new File(cqlshExecutable);
        File originalCqlshRenamed = new File(workingDirectory + "cqlsh_old");
        boolean renamedTo = originalCqlsh.renameTo(originalCqlshRenamed);
        if (!renamedTo) {
            throw new FileSystemException("Cannot rename cqlsh executable to path it.");
        }
        try(FileInputStream fis = new FileInputStream(originalCqlshRenamed)) {
            try(BufferedReader in = new BufferedReader(new InputStreamReader(fis))) {
                try(FileWriter fw = new FileWriter(originalCqlsh, true)) {
                    try(BufferedWriter out = new BufferedWriter(fw)) {
                        String aLine;
                        while ((aLine = in.readLine()) != null) {
                            out.write(aLine.replace(VERSION_TO_REPLACE, VERSION_REPLACED));
                            out.newLine();
                        }
                    }
                }
            }
        } catch (FileNotFoundException e) {
            throw new FileSystemException("Cannot find cql file to patch it", e);
        } catch (IOException e) {
            throw new FileSystemException("Cannot edit cql executable to patch it", e);
        }
    }

    /**
     * Download tar archive and decompress.
     */
    public void install() {
        try {
            LoggerShell.info("Downloading Cqlshell, please wait...");
            String tarArchive = AstraCliUtils.ASTRA_HOME + File.separator + settings.tarball();
            FileUtils.downloadFile(settings.url(), tarArchive);

            LoggerShell.info("Installing  archive, please wait...");
            FileUtils.extactTargzInAstraCliHome(new File(tarArchive));
            patchCqlshInstallation();
            if (!new File(cqlshExecutable).setExecutable(true, false)) {
                throw new FileSystemException("Cannot make cqlshell executable. ");
            }
            if (new File(tarArchive).delete())
                LoggerShell.success("Cqlsh has been installed");
        } catch (IOException e) {
            throw new FileSystemException("Cannot install CqlShell :" + e.getMessage(), e);
        }
    }
    
    /**
     * Initialize CqlShell command line.
     *
     * @param options
     *      options in the command line
     * @param db
     *      current database
     * @return
     *      command line
     */
    private List<String> buildCommandLine(CqlShellOption options, Database db) {
        List<String> cqlSh = new ArrayList<>();
        cqlSh.add(cqlshExecutable);
        // Credentials
        cqlSh.add("-u");cqlSh.add("token");
        cqlSh.add("-p");cqlSh.add(CliContext.getInstance().getToken());
        cqlSh.add("-b");
        File scb = new File(AstraCliUtils.ASTRA_HOME + File.separator + AstraCliUtils.SCB_FOLDER + File.separator +
                AstraClientConfig.buildScbFileName(db.getId(), db.getInfo().getRegion()));
        if (!scb.exists()) {
            LoggerShell.error("Cloud Secure Bundle '" + scb.getAbsolutePath() + "' has not been found.");
                    throw new SecureBundleNotFoundException(scb.getAbsolutePath());
        }
        cqlSh.add(scb.getAbsolutePath());
        
        // -- Custom options of Cqlsh itself
        if (options.debug())
            cqlSh.add("--debug");
        if (options.version())
            cqlSh.add("--version");
        if (options.execute() != null) {
            cqlSh.add("-e");
            cqlSh.add(options.execute());
        }
        if (options.file() != null) {
            cqlSh.add("-f");
            cqlSh.add(options.file());
        }
        if (options.keyspace() != null) {
            cqlSh.add("-k");
            cqlSh.add(options.keyspace());
        }
        if (options.encoding() != null) {
            cqlSh.add("--encoding");
            cqlSh.add(options.encoding() );
        }
        return cqlSh;
    }
    
    /**
     * Start CqlShell when needed.
     * 
     * @param options
     *      shell options
     * @param database
     *      current db
     */
    public void run(CqlShellOption options, String database) {
        
        // Install Cqlsh for Astra and set permissions
        if (!isInstalled()) install();
        
        // Download scb and throw DatabaseNotFound.
        dbDao.downloadCloudSecureBundles(database);    
        
        try {
            List <String > commands = buildCommandLine(options, dbDao.getDatabase(database));
            LoggerShell.debug("RUNNING: " + String.join(" ", commands));
            LoggerShell.info("\nCqlsh is starting, please wait for connection establishment...");
            new ProcessBuilder(commands.toArray(new String[0])).inheritIO().start().waitFor();
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            throw new CannotStartProcessException("cqlsh", e);
        }
    }

}