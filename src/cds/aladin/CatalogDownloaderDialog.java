package cds.aladin;

import java.awt.*;
import java.io.File;
import javax.swing.*;

public class CatalogDownloaderDialog extends JDialog implements CatalogDownloader.DownloaderListener {

    private JComboBox<String> sourceCombo;
    private JTextField catalogField;
    private JCheckBox allSkyCheck;
    private JTextField raField;
    private JTextField decField;
    private JTextField radiusField;
    private JTextField fileField;
    private JButton startBtn;
    private JButton cancelBtn;
    private JLabel progressLabel;
    private JCheckBox offlineCb;
    
    private boolean isDownloading = false;

    private final Aladin aladin;

    public CatalogDownloaderDialog(Window owner) { this(owner, null); }

    public CatalogDownloaderDialog(Window owner, Aladin aladin) {
        super(owner, "VizieR / Simbad Downloader", ModalityType.MODELESS);
        this.aladin = aladin;
        setLayout(new BorderLayout(10, 10));
        
        JPanel inputPanel = new JPanel(new GridBagLayout());
        inputPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        // Source
        gbc.gridx = 0; gbc.gridy = 0;
        inputPanel.add(new JLabel("Source:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        sourceCombo = new JComboBox<>(new String[] {"VizieR", "Simbad"});
        inputPanel.add(sourceCombo, gbc);
        
        // Catalog
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1;
        inputPanel.add(new JLabel("Catalog (VizieR):"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        catalogField = new JTextField("I/355/gaiadr3");
        inputPanel.add(catalogField, gbc);
        
        sourceCombo.addActionListener(e -> {
            boolean isViz = "VizieR".equals(sourceCombo.getSelectedItem());
            catalogField.setEnabled(isViz);
        });
        
        // Region
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1;
        inputPanel.add(new JLabel("Region (RA, DEC, Rad):"), gbc);
        
        JPanel regionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        raField = new JTextField("0.0", 5);
        decField = new JTextField("0.0", 5);
        radiusField = new JTextField("1.0", 5);
        regionPanel.add(new JLabel("RA:")); regionPanel.add(raField);
        regionPanel.add(new JLabel("DEC:")); regionPanel.add(decField);
        regionPanel.add(new JLabel("Rad (deg):")); regionPanel.add(radiusField);
        
        gbc.gridx = 1; gbc.gridwidth = 2;
        inputPanel.add(regionPanel, gbc);
        
        // All Sky
        gbc.gridx = 1; gbc.gridy = 3; gbc.gridwidth = 2;
        allSkyCheck = new JCheckBox("All Sky (ignore region)");
        allSkyCheck.addActionListener(e -> {
            boolean enable = !allSkyCheck.isSelected();
            raField.setEnabled(enable);
            decField.setEnabled(enable);
            radiusField.setEnabled(enable);
        });
        inputPanel.add(allSkyCheck, gbc);
        
        // Output File
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 1;
        inputPanel.add(new JLabel("Output File:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 1;
        fileField = new JTextField(System.getProperty("user.home") + File.separator + "Astrovia" + File.separator + "Catalogs" + File.separator + "catalog.vot");
        inputPanel.add(fileField, gbc);
        gbc.gridx = 2;
        JButton browseBtn = new JButton("Browse...");
        browseBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(new File(fileField.getText()).getParentFile());
            if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                fileField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        inputPanel.add(browseBtn, gbc);

        // Keep it in the collections list
        gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 1;
        inputPanel.add(new JLabel("Library:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        offlineCb = new JCheckBox("Add to \"Catalogs offline\" when finished (always available in the collections list)", true);
        offlineCb.setEnabled(aladin != null);
        inputPanel.add(offlineCb, gbc);
        
        add(inputPanel, BorderLayout.CENTER);
        
        // Progress & Buttons
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        
        progressLabel = new JLabel("Ready.");
        bottomPanel.add(progressLabel, BorderLayout.CENTER);
        
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        startBtn = new JButton("Download");
        cancelBtn = new JButton("Cancel");
        cancelBtn.setEnabled(false);
        
        startBtn.addActionListener(e -> startDownload());
        cancelBtn.addActionListener(e -> {
            // Cancel not fully implemented for URLConnection, but we reset UI
            resetUI();
            progressLabel.setText("Cancelled by user (download might continue in background).");
        });
        
        btnPanel.add(startBtn);
        btnPanel.add(cancelBtn);
        bottomPanel.add(btnPanel, BorderLayout.EAST);
        
        add(bottomPanel, BorderLayout.SOUTH);
        
        pack();
        setLocationRelativeTo(owner);
    }
    
    private void startDownload() {
        String source = (String) sourceCombo.getSelectedItem();
        String catalog = catalogField.getText().trim();
        File file = new File(fileField.getText());
        
        double ra = 0, dec = 0, radius = 0;
        if (!allSkyCheck.isSelected()) {
            try {
                ra = Double.parseDouble(raField.getText());
                dec = Double.parseDouble(decField.getText());
                radius = Double.parseDouble(radiusField.getText());
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Invalid region coordinates.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        } else {
            radius = -1; // Flag for all sky
        }
        
        // Name under which it will appear in "Catalogs offline", and a file name that says what it is
        offlineLabel = source + (source.equals("VizieR") ? " " + catalog : "")
                + (radius < 0 ? " all-sky" : String.format(java.util.Locale.US, " (%.3f %+.3f r=%.3f)", ra, dec, radius));
        if (file.getName().equals("catalog.vot")) {
            String base = offlineLabel.replaceAll("[^A-Za-z0-9._-]+", "_");
            file = new File(file.getParentFile(), base + ".vot");
            fileField.setText(file.getAbsolutePath());
        }
        isDownloading = true;
        startBtn.setEnabled(false);
        cancelBtn.setEnabled(true);
        progressLabel.setText("Connecting...");
        
        CatalogDownloader.download(source, catalog, ra, dec, radius, file, this);
    }
    
    private void resetUI() {
        isDownloading = false;
        startBtn.setEnabled(true);
        cancelBtn.setEnabled(false);
    }

    @Override
    public void onProgress(long totalBytesDownloaded) {
        if (!isDownloading) return;
        String sizeStr = String.format("%.2f MB", totalBytesDownloaded / (1024.0 * 1024.0));
        progressLabel.setText("Downloaded: " + sizeStr);
    }

    private String warning = null;
    private String offlineLabel = null;

    @Override
    public void onWarning(String message) { warning = message; }

    @Override
    public void onComplete() {
        if (!isDownloading) return;
        resetUI();
        progressLabel.setText(warning != null ? "Completed with a warning" : "Completed!");
        JOptionPane.showMessageDialog(this, warning != null ? warning : "Download successfully completed!",
                warning != null ? "Incomplete table" : "Success",
                warning != null ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
        warning = null;
        // A downloaded catalogue used to be saved with no way to use it: keep it in the list and offer to show it.
        if (aladin != null) {
            File saved = new File(fileField.getText());
            if (saved.isFile() && offlineCb.isSelected() && OfflineCollections.addCatalog(aladin, saved, offlineLabel)) {
                JOptionPane.showMessageDialog(this, "Added to \"Catalogs offline\": it is now in the collections list.",
                        "Catalogs offline", JOptionPane.INFORMATION_MESSAGE);
            }
            if (saved.isFile() && JOptionPane.showConfirmDialog(this, "Show this catalogue on the sky now?\n" + saved.getName(),
                    "Show catalogue", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                aladin.execAsyncCommand("load \"" + saved.getAbsolutePath().replace("\\", "/") + "\"");
            }
        }
    }

    @Override
    public void onError(String message) {
        if (!isDownloading) return;
        resetUI();
        progressLabel.setText("Error: " + message);
        JOptionPane.showMessageDialog(this, "Error during download:\n" + message, "Error", JOptionPane.ERROR_MESSAGE);
    }
}
