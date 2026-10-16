package cds.aladin;

import java.awt.*;
import java.io.File;
import javax.swing.*;

public class HiPSDownloaderDialog extends JDialog implements HiPSDownloader.HiPSDownloaderListener {

    private JComboBox<String> urlCombo;
    private JSpinner orderSpinner;
    private JTextField dirField;
    private JCheckBox fastCheckCb;
    private JSpinner threadSpinner;
    private JButton addBtn;
    private JButton startBtn;
    private JButton cancelBtn;
    private JLabel progressLabel;
    
    private HiPSDownloader downloader;
    private long startTime;

    private final Aladin aladin;
    private JCheckBox areaCb;
    private JLabel estimateLabel;

    public HiPSDownloaderDialog(Window owner) { this(owner, null); }

    public HiPSDownloaderDialog(Window owner, Aladin aladin) {
        super(owner, "HiPS Dataset Downloader", Dialog.ModalityType.MODELESS);
        this.aladin = aladin;
        setLayout(new BorderLayout(10, 10));
        
        JPanel inputPanel = new JPanel(new GridBagLayout());
        inputPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        // URL
        gbc.gridx = 0; gbc.gridy = 0;
        inputPanel.add(new JLabel("HiPS URL:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        urlCombo = new JComboBox<>(new String[] {
            "https://alasky.cds.unistra.fr/DSS/DSSColor/",
            "https://alasky.cds.unistra.fr/2MASS/Color/"
        });
        urlCombo.setEditable(true);
        inputPanel.add(urlCombo, gbc);
        
        // Max Order
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1;
        inputPanel.add(new JLabel("Max Order:"), gbc);
        gbc.gridx = 1;
        orderSpinner = new JSpinner(new SpinnerNumberModel(6, 0, 14, 1));
        inputPanel.add(orderSpinner, gbc);
        
        // Target Dir
        gbc.gridx = 0; gbc.gridy = 2;
        inputPanel.add(new JLabel("Output Dir:"), gbc);
        gbc.gridx = 1;
        dirField = new JTextField(System.getProperty("user.home") + File.separator + "HiPS");
        inputPanel.add(dirField, gbc);
        gbc.gridx = 2;
        JButton browseBtn = new JButton("Browse...");
        browseBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(dirField.getText());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                dirField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        inputPanel.add(browseBtn, gbc);
        
        // Fast Check
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 1;
        inputPanel.add(new JLabel("Fast Check:"), gbc);
        gbc.gridx = 1;
        fastCheckCb = new JCheckBox("Skip existing files");
        fastCheckCb.setSelected(true);
        inputPanel.add(fastCheckCb, gbc);
        
        // Threads
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 1;
        inputPanel.add(new JLabel("Max Threads:"), gbc);
        gbc.gridx = 1;
        threadSpinner = new JSpinner(new SpinnerNumberModel(8, 1, 32, 1));
        inputPanel.add(threadSpinner, gbc);

        // Only the area in view: the whole sky to a useful order is hundreds of GB.
        gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 1;
        inputPanel.add(new JLabel("Area:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        areaCb = new JCheckBox("Only the area in view", aladin != null);
        areaCb.setEnabled(aladin != null);
        inputPanel.add(areaCb, gbc);
        gbc.gridx = 1; gbc.gridy = 6; gbc.gridwidth = 2;
        estimateLabel = new JLabel(" ");
        inputPanel.add(estimateLabel, gbc);
        javax.swing.event.ChangeListener est = e -> updateEstimate();
        orderSpinner.addChangeListener(est);
        areaCb.addChangeListener(est);
        dirField.addActionListener(e -> updateEstimate());
        updateEstimate();
        
        add(inputPanel, BorderLayout.CENTER);
        
        // Progress & Buttons
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        
        progressLabel = new JLabel("Ready.");
        bottomPanel.add(progressLabel, BorderLayout.CENTER);
        
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        addBtn = new JButton("Add Folder Only");
        startBtn = new JButton("Start");
        cancelBtn = new JButton("Cancel");
        cancelBtn.setEnabled(false);
        
        addBtn.addActionListener(e -> {
            LocalHiPSManager.addLocalHiPS((String) urlCombo.getSelectedItem(), dirField.getText());
            JOptionPane.showMessageDialog(this, "Folder added to local catalog!", "Success", JOptionPane.INFORMATION_MESSAGE);
            dispose();
        });
        startBtn.addActionListener(e -> startDownload());
        cancelBtn.addActionListener(e -> cancelDownload());
        
        btnPanel.add(addBtn);
        btnPanel.add(startBtn);
        btnPanel.add(cancelBtn);
        bottomPanel.add(btnPanel, BorderLayout.EAST);
        
        add(bottomPanel, BorderLayout.SOUTH);
        
        pack();
        setLocationRelativeTo(owner);
    }
    
    /** The part of the sky on screen (centre + radius), or null when there is no usable view. */
    private HiPSDownloader.Region currentRegion() {
        if (aladin == null || !areaCb.isSelected()) return null;
        try {
            ViewSimple v = aladin.view.getCurrentView();
            if (v == null || v.isFree()) return null;
            Coord c = v.getCooCentre();
            double radius = Math.max(v.getTailleRA(), v.getTailleDE()) / 2.0 * 1.25;
            if (c == null || Double.isNaN(c.al) || Double.isNaN(radius)) return null;
            return new HiPSDownloader.Region(c.al, c.del, Math.max(0.01, Math.min(90.0, radius)));
        } catch (Throwable t) {
            return null;
        }
    }

    private void updateEstimate() {
        int order = (Integer)orderSpinner.getValue();
        long[] est = HiPSDownloader.estimate(order, currentRegion(), "jpg");
        long free = HiPSDownloader.freeBytes(new File(dirField.getText()));
        boolean tooBig = est[1] > free * 0.9 || est[0] > HiPSDownloader.MAX_TILES;
        estimateLabel.setText("About " + est[0] + " tiles, " + HiPSDownloader.human(est[1]) + " (" + HiPSDownloader.human(free) + " free)");
        estimateLabel.setForeground(tooBig ? new Color(0xEF, 0x44, 0x44) : UIManager.getColor("Label.foreground"));
        if (startBtn != null) startBtn.setEnabled(!tooBig && (cancelBtn == null || !cancelBtn.isEnabled()));
    }

    private void startDownload() {
        String url = (String) urlCombo.getSelectedItem();
        File dir = new File(dirField.getText());
        int maxOrder = (Integer) orderSpinner.getValue();
        
        if (url == null || url.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please specify a HiPS URL", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        startBtn.setEnabled(false);
        cancelBtn.setEnabled(true);
        progressLabel.setText("Starting...");
        
        boolean fastCheck = fastCheckCb.isSelected();
        int threads = (Integer) threadSpinner.getValue();
        
        startTime = System.currentTimeMillis();
        downloader = new HiPSDownloader(url.trim(), dir, maxOrder, fastCheck, threads, currentRegion(), this);
        downloader.start();
    }
    
    private void cancelDownload() {
        if (downloader != null) {
            downloader.cancel();
        }
        startBtn.setEnabled(true);
        cancelBtn.setEnabled(false);
        progressLabel.setText("Cancelled.");
    }

    @Override
    public void onProgress(int tilesDownloaded, long totalBytesDownloaded, int activeTasks) {
        long elapsed = System.currentTimeMillis() - startTime;
        double speed = 0;
        if (elapsed > 0) {
            speed = (totalBytesDownloaded / 1024.0) / (elapsed / 1000.0);
        }
        String sizeStr = String.format("%.2f MB", totalBytesDownloaded / (1024.0 * 1024.0));
        String speedStr = String.format("%.2f KB/s", speed);
        
        progressLabel.setText(String.format("Tiles: %d | Size: %s | Speed: %s | Active Tasks: %d", 
                tilesDownloaded, sizeStr, speedStr, activeTasks));
    }

    private String summary = null;
    private boolean incomplete = false;

    @Override
    public void onSummary(String summary, boolean incomplete) {
        this.summary = summary;
        this.incomplete = incomplete;
    }

    @Override
    public void onComplete() {
        startBtn.setEnabled(true);
        cancelBtn.setEnabled(false);
        progressLabel.setText(summary != null ? summary : progressLabel.getText() + " - Completed!");
        LocalHiPSManager.addLocalHiPS((String) urlCombo.getSelectedItem(), dirField.getText());
        JOptionPane.showMessageDialog(this, summary != null ? summary : "Download completed!",
                incomplete ? "Finished with errors" : "Success",
                incomplete ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
    }

    @Override
    public void onError(String message) {
        startBtn.setEnabled(true);
        cancelBtn.setEnabled(false);
        progressLabel.setText("Error: " + message);
        JOptionPane.showMessageDialog(this, "Error: " + message, "Error", JOptionPane.ERROR_MESSAGE);
    }
}
