/*
 * Phonalyser - precision audio measurement workbench.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.edgo.audio.measure.gui.backend.net;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.GuiUtil;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.net.client.BeaconListener;
import org.edgo.audio.measure.net.client.NetPreferences;
import org.edgo.audio.measure.net.client.NetServerEntry;
import org.edgo.audio.measure.net.client.ServerProber;
import org.edgo.audio.measure.net.proto.NetProto;

import lombok.extern.log4j.Log4j2;

/**
 * The server list: which Phonalyser servers are in the room, which one this
 * installation is talking to, and how to reach one discovery cannot see.
 *
 * <p>Not an SWT {@code Dialog} subclass - this app builds its own shells, and
 * subclassing one throws at runtime with no compile warning.
 *
 * <p><b>It shows; it does not own.</b>  Which servers exist is
 * {@link NetServerList}'s, the session is {@link NetBenchUi}'s, and both outlive
 * this window: the operator connects here and then goes on to pick a backend in
 * the Preferences dialog, which is still open behind it.  What is genuinely this
 * type's own is the two things that exist only while the window does - the
 * discovery socket and the repaint timer that lets a bench go grey when its
 * beacons stop.
 *
 * <p><b>Nothing here is saved.</b>  A server added or removed goes into the
 * settings block's EDIT copy, so the Preferences dialog's Cancel drops it and
 * its OK writes it - the same staging rule as the QA40x's ranges.  A connection,
 * on the other hand, is live the moment it is made: it is an action, not a
 * setting.
 */
@Log4j2
public final class NetServerListDialog {

    private static final int DIALOG_MARGIN = 12;
    private static final int BUTTON_SPACING = 8;
    /** How often the rows are re-drawn while the window is up.  One beacon
     *  period: a server that stops announcing goes grey within a period of the
     *  expiry rather than at the next click. */
    private static final int REFRESH_MS = NetProto.BEACON_INTERVAL_MS;
    private static final int TABLE_HEIGHT_HINT = 180;
    private static final int NAME_COLUMN_PX = 220;
    private static final int HOST_COLUMN_PX = 160;
    private static final int STATE_COLUMN_PX = 110;
    private static final int PORT_FIELD_PX = 70;
    private static final int HOST_FIELD_PX = 160;
    private static final int HINT_WIDTH_PX = 520;
    private static final int MAX_PORT = 65_535;
    /** What an empty proxy-port field means: no proxy.  The ignore-rule itself
     *  is {@code NetPreferences}'; this is only how the widget spells "empty". */
    private static final int NO_PORT = 0;
    /** What a connect or a probe answers with when the operator stopped waiting
     *  or the attempt failed outright.  Both report SUCCESS as null and a problem
     *  as the sentence to show, so "no answer at all" needs a third value - and it
     *  is blank because the operator who pressed Cancel does not need to be told
     *  what they just did. */
    private static final String NO_ANSWER = "";

    private final Shell parent;
    private final NetBenchUi bench;
    private final NetServerList servers;

    /** Discovery, alive only while the window is: a client that kept the group
     *  joined for the whole session would hold a socket for a list nobody is
     *  looking at. */
    private BeaconListener discovery;
    /** Unicast liveness for the MANUAL rows, whose beacons a router eats - same
     *  window-bound lifecycle as {@link #discovery}. */
    private ServerProber prober;
    private Shell dialog;
    private Table table;
    private Text hostField;
    private Text portField;
    private Text proxyHostField;
    private Text proxyPortField;
    private Button connectButton;
    private Button disconnectButton;
    private Button removeButton;

    public NetServerListDialog(Shell parent, NetBenchUi bench) {
        this.parent = parent;
        this.bench = bench;
        this.servers = bench.getServers();
    }

    /** Shows the window modally and returns once it is closed. */
    public void open() {
        build();
        startDiscovery();
        refresh();
        dialog.pack();
        // The initial size is the minimum, the same treatment the Preferences
        // dialog gets.
        dialog.setMinimumSize(dialog.getSize());
        Dialogs.centerOnParent(dialog);
        dialog.open();
        Display display = parent.getDisplay();
        while (!dialog.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Widgets
    // -------------------------------------------------------------------------

    private void build() {
        dialog = new Shell(parent, SWT.RESIZE | SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        dialog.setText(I18n.t("net.servers.title"));
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = DIALOG_MARGIN;
        layout.marginHeight = DIALOG_MARGIN;
        layout.verticalSpacing = DIALOG_MARGIN;
        dialog.setLayout(layout);

        Label hint = new Label(dialog, SWT.WRAP);
        hint.setText(I18n.t("net.servers.hint"));
        GridData hintData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        hintData.widthHint = HINT_WIDTH_PX;
        hint.setLayoutData(hintData);

        table = new Table(dialog, SWT.SINGLE | SWT.BORDER | SWT.FULL_SELECTION);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableData.heightHint = TABLE_HEIGHT_HINT;
        table.setLayoutData(tableData);
        addColumn("net.servers.column.name", NAME_COLUMN_PX);
        addColumn("net.servers.column.address", HOST_COLUMN_PX);
        addColumn("net.servers.column.state", STATE_COLUMN_PX);
        table.addListener(SWT.Selection, e -> refreshButtons());
        // Double-click (and Enter) on a row connects it - the same code path the
        // Connect button spends, so the staging rules and the refusal dialog are
        // the same whichever way the operator got there.
        table.addListener(SWT.DefaultSelection, e -> connectSelected());

        buildActionRow();

        // ONE grid for the proxy row and the manual-add row, so their address
        // fields share column borders: labels left, fields growing with the
        // window.
        Composite entryRows = new Composite(dialog, SWT.NONE);
        entryRows.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout entryLayout = new GridLayout(5, false);
        entryLayout.marginWidth = 0;
        entryLayout.marginHeight = 0;
        entryRows.setLayout(entryLayout);
        buildProxyRow(entryRows);
        buildAddRow(entryRows);

        Composite closeRow = new Composite(dialog, SWT.NONE);
        closeRow.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
        RowLayout closeLayout = new RowLayout(SWT.HORIZONTAL);
        closeLayout.spacing = BUTTON_SPACING;
        closeRow.setLayout(closeLayout);
        Button closeButton = new Button(closeRow, SWT.PUSH);
        closeButton.setText(I18n.t("common.close"));
        closeButton.addListener(SWT.Selection, e -> dialog.close());
        dialog.setDefaultButton(closeButton);

        dialog.addDisposeListener(e -> stopDiscovery());
    }

    private void buildActionRow() {
        Composite row = new Composite(dialog, SWT.NONE);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        RowLayout rowLayout = new RowLayout(SWT.HORIZONTAL);
        rowLayout.spacing = BUTTON_SPACING;
        rowLayout.marginWidth = 0;
        row.setLayout(rowLayout);

        connectButton = new Button(row, SWT.PUSH);
        connectButton.setText(I18n.t("net.servers.connect"));
        connectButton.addListener(SWT.Selection, e -> connectSelected());

        disconnectButton = new Button(row, SWT.PUSH);
        disconnectButton.setText(I18n.t("net.servers.disconnect"));
        disconnectButton.addListener(SWT.Selection, e -> {
            // Not the no-op it looks like: the generator lane and every device
            // lock go back ON THE WIRE before the farewell.  DIRECT on the
            // operator's thread, bounded by the wire timeouts.
            disconnectQuietly();
            refresh();
        });

        removeButton = new Button(row, SWT.PUSH);
        removeButton.setText(I18n.t("net.servers.remove"));
        removeButton.setToolTipText(I18n.t("net.servers.remove.tooltip"));
        removeButton.addListener(SWT.Selection, e -> removeSelected());
    }

    /**
     * The proxy an operator's network puts between this workbench and a routed
     * bench.  BOTH fields empty - or either of them - means no proxy at all and
     * a direct connection; that rule lives in {@code NetPreferences}, so this
     * row only carries the text.
     *
     * <p>It sits between the list's actions and the manual entry because it
     * belongs to both: the Connect above it and the Add below it dial through
     * it, and so does the liveness poll behind the rows.
     *
     * <p>No Apply button, and none is missing: the fields write the EDIT copy on
     * every keystroke, everything in this window already reads that copy, and
     * the Preferences dialog's OK is what makes it permanent - the same staging
     * rule as the server list itself.
     */
    private void buildProxyRow(Composite parent) {
        NetPreferences prefs = bench.getPreferences();
        Label rowLabel = new Label(parent, SWT.NONE);
        rowLabel.setText(I18n.t("net.servers.proxy.title"));
        proxyHostField = new Text(parent, SWT.SINGLE | SWT.BORDER);
        proxyHostField.setText(prefs.getProxyHostEdit() == null ? "" : prefs.getProxyHostEdit());
        GridData hostData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        hostData.widthHint = HOST_FIELD_PX;
        proxyHostField.setLayoutData(hostData);
        proxyHostField.addListener(SWT.Modify,
                e -> prefs.setProxyHostEdit(proxyHostField.getText().trim()));

        Label portLabel = new Label(parent, SWT.NONE);
        portLabel.setText(I18n.t("net.servers.proxy.port"));
        proxyPortField = new Text(parent, SWT.SINGLE | SWT.BORDER);
        int port = prefs.getProxyPortEdit();
        proxyPortField.setText(port == NO_PORT ? "" : Integer.toString(port));
        // Spans the Add button's column too - this row has no fifth widget.
        GridData portData = new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1);
        portData.widthHint = PORT_FIELD_PX;
        proxyPortField.setLayoutData(portData);
        proxyPortField.addListener(SWT.Modify, e -> prefs.setProxyPortEdit(proxyPort()));
    }

    /** The manual entry of spec 2.1's blind spot: discovery is link-local, so a
     *  bench on a routed subnet is reached by an address the operator types. */
    private void buildAddRow(Composite parent) {
        Label rowLabel = new Label(parent, SWT.NONE);
        rowLabel.setText(I18n.t("net.servers.add.title"));
        hostField = new Text(parent, SWT.SINGLE | SWT.BORDER);
        GridData hostData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        hostData.widthHint = HOST_FIELD_PX;
        hostField.setLayoutData(hostData);

        Label portLabel = new Label(parent, SWT.NONE);
        portLabel.setText(I18n.t("net.servers.port"));
        // ONE field: a server serves its HTTP endpoints and its control channel
        // on the same port, so there is one number to type.
        portField = new Text(parent, SWT.SINGLE | SWT.BORDER);
        portField.setText(Integer.toString(NetProto.DEFAULT_PORT));
        GridData portData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        portData.widthHint = PORT_FIELD_PX;
        portField.setLayoutData(portData);

        Button add = new Button(parent, SWT.PUSH);
        add.setText(I18n.t("net.servers.add"));
        add.setToolTipText(I18n.t("net.servers.add.tooltip"));
        add.addListener(SWT.Selection, e -> addTypedServer());
    }

    private void addColumn(String labelKey, int widthPx) {
        TableColumn column = new TableColumn(table, SWT.NONE);
        column.setText(I18n.t(labelKey));
        column.setWidth(widthPx);
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    /**
     * Adds the typed address - after asking whether a Phonalyser server actually
     * answers there.  The probe is what turns an address into an ENTRY: a
     * remembered server is keyed by its installation id (spec 2.1), and only the
     * server can say what its id and its name are.
     */
    private void addTypedServer() {
        String host = hostField.getText().trim();
        int port = port(portField);
        if (host.isEmpty() || port < 0) {
            Dialogs.error(dialog, I18n.t("net.servers.error.title"),
                    I18n.t("net.servers.error.address"));
            return;
        }
        // The id is unknown until the server answers; the host stands in as a
        // key for the round trip and is replaced by what the hello reports.
        // A typed address is the one most likely to be wrong, so this is the
        // probe most likely to sit out its whole connect timeout.
        NetServerEntry candidate = new NetServerEntry(host, host, host, port, true);
        // NO_ANSWER, not null: probe reports SUCCESS as null, so a call that
        // failed must come back as something else - otherwise giving up on a
        // dead address would look exactly like finding a server there.  DIRECT,
        // bounded by the wire timeouts.
        String failure;
        try {
            failure = bench.probe(candidate);
        } catch (RuntimeException ex) {
            log.warn("server probe failed: {}", ex.toString());
            failure = NO_ANSWER;
        }
        if (failure != null) {
            if (!failure.isEmpty()) {
                Dialogs.error(dialog, I18n.t("net.servers.error.title"), failure);
            }
            return;
        }
        hostField.setText("");
        refresh();
    }

    /** Connects the selected row - from the button, and from a double-click on
     *  the row itself.  A row that is already the connected server is not
     *  connectable, so neither way re-opens a session over the live one. */
    private void connectSelected() {
        NetServerEntry selected = selectedServer();
        if (!servers.connectable(selected, bench.getConnectedServer())) {
            return;
        }
        // Same NO_ANSWER rule as the probe: connect reports success as null.
        String failure;
        try {
            failure = bench.connect(selected);
        } catch (RuntimeException ex) {
            log.warn("server connect failed: {}", ex.toString());
            failure = NO_ANSWER;
        }
        if (failure != null && !failure.isEmpty()) {
            Dialogs.error(dialog, I18n.t("net.servers.error.title"), failure);
        }
        refresh();
    }

    /** Forgets a server the operator typed in.  A discovered one is not
     *  removable: the next beacon would put it straight back, so the button
     *  would look broken rather than restrictive. */
    private void removeSelected() {
        NetServerEntry selected = selectedServer();
        if (selected == null || !selected.manual()) {
            return;
        }
        NetServerEntry connected = bench.getConnectedServer();
        if (connected != null && connected.serverId().equals(selected.serverId())) {
            // Forgetting the bench being measured on ends its session first, and
            // that teardown talks to it - see the Disconnect button.
            disconnectQuietly();
        }
        servers.forget(selected.serverId());
        refresh();
    }

    /** Disconnect on the caller's thread, never letting a dead wire throw into
     *  a button handler - the round trips are bounded by the connection's own
     *  timeouts. */
    private void disconnectQuietly() {
        try {
            bench.disconnect();
        } catch (RuntimeException ex) {
            log.warn("server disconnect failed: {}", ex.toString());
        }
    }

    /** The proxy port typed in its field, or {@link #NO_PORT} when it is empty
     *  or not a number yet - empty means no proxy, and half-typed text is what
     *  a field looks like on the way to a number, not an error to report. */
    private int proxyPort() {
        try {
            return Integer.parseInt(proxyPortField.getText().trim());
        } catch (NumberFormatException e) {
            return NO_PORT;
        }
    }

    /** The port typed in the field, or -1 when it is not one - the caller turns
     *  that into the address message. */
    private int port(Text field) {
        try {
            int value = Integer.parseInt(field.getText().trim());
            return value > 0 && value <= MAX_PORT ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private NetServerEntry selectedServer() {
        int index = table.getSelectionIndex();
        if (index < 0 || !(table.getItem(index).getData() instanceof NetServerEntry server)) {
            return null;
        }
        return server;
    }

    // -------------------------------------------------------------------------
    // Discovery and rendering
    // -------------------------------------------------------------------------

    /** Joins the discovery group and asks every server on it to speak up at once
     *  (spec 2.1's probe), so the list is populated before the next beacon
     *  period rather than half a second into it. */
    private void startDiscovery() {
        Display display = dialog.getDisplay();
        discovery = new BeaconListener(bench.getCodec(), (beacon, host) ->
                GuiUtil.marshal(dialog, () -> {
                    // On the UI thread on purpose: the model writes into the
                    // settings block the rest of this dialog reads.
                    servers.heard(beacon, host, System.currentTimeMillis());
                    refresh();
                }));
        discovery.start();
        discovery.probe();
        // The manual rows' beacons never cross their router; their liveness is a
        // unicast GET /info instead.  Same UI-thread marshal as the beacons - the
        // stamp lands in the model the widgets read, and the 500 ms tick repaints.
        prober = new ServerProber(bench.getCodec(), serverId ->
                GuiUtil.marshal(dialog,
                        () -> servers.alive(serverId, System.currentTimeMillis())));
        prober.setTargets(manualRows());
        // The proxy BEFORE the thread starts, beside the targets: the first
        // round begins the moment start() returns, and refresh() only reaches
        // it a repaint later - so without this the first poll of every routed
        // row would go direct, waste its whole timeout and leave the row grey
        // for a second longer than it needs to be.
        prober.setProxy(bench.getPreferences().proxyEdit());
        prober.start();
        display.timerExec(REFRESH_MS, this::tick);
    }

    private void stopDiscovery() {
        if (discovery != null) {
            discovery.stop();
            discovery = null;
        }
        if (prober != null) {
            prober.stop();
            prober = null;
        }
    }

    /** The rows only a unicast probe can vouch for - the manually added ones. */
    private List<NetServerEntry> manualRows() {
        List<NetServerEntry> manual = new ArrayList<>();
        for (NetServerList.Row row : servers.rows(System.currentTimeMillis())) {
            if (row.server().manual()) {
                manual.add(row.server());
            }
        }
        return manual;
    }

    /** Re-draws while the window is up, so a server whose beacons stopped turns
     *  offline on its own instead of at the next click. */
    private void tick() {
        if (dialog.isDisposed()) {
            return;
        }
        refresh();
        dialog.getDisplay().timerExec(REFRESH_MS, this::tick);
    }

    /** Rebuilds the rows from the model, keeping the operator's selection on the
     *  server it was on rather than on the row number it happened to be at. */
    private void refresh() {
        if (table.isDisposed()) {
            return;
        }
        NetServerEntry selected = selectedServer();
        NetServerEntry connected = bench.getConnectedServer();
        table.removeAll();
        List<NetServerList.Row> rows = servers.rows(System.currentTimeMillis());
        for (NetServerList.Row row : rows) {
            NetServerEntry server = row.server();
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(new String[] {server.name(), address(server), state(row, connected)});
            item.setData(server);
            if (selected != null && selected.serverId().equals(server.serverId())) {
                table.setSelection(item);
            }
        }
        if (prober != null) {
            prober.setTargets(manualRows());
            // Replaced whole on every repaint, exactly like the targets: the
            // operator can type a proxy while this is already polling, and the
            // rows it polls are the routed ones a proxy exists for.
            prober.setProxy(bench.getPreferences().proxyEdit());
        }
        refreshButtons();
    }

    private String address(NetServerEntry server) {
        return server.host() == null ? "" : server.host() + ":" + server.port();
    }

    private String state(NetServerList.Row row, NetServerEntry connected) {
        if (connected != null && connected.serverId().equals(row.server().serverId())) {
            return I18n.t("net.servers.state.connected");
        }
        return I18n.t(row.online() ? "net.servers.state.online" : "net.servers.state.offline");
    }

    private void refreshButtons() {
        NetServerEntry selected = selectedServer();
        connectButton.setEnabled(servers.connectable(selected, bench.getConnectedServer()));
        disconnectButton.setEnabled(bench.isConnected());
        removeButton.setEnabled(selected != null && selected.manual());
    }
}
