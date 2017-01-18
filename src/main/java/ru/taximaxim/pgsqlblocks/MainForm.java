package ru.taximaxim.pgsqlblocks;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.*;
import org.eclipse.jface.window.ApplicationWindow;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.ShellAdapter;
import org.eclipse.swt.events.ShellEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import ru.taximaxim.pgsqlblocks.dbcdata.*;
import ru.taximaxim.pgsqlblocks.process.Process;
import ru.taximaxim.pgsqlblocks.process.ProcessTreeContentProvider;
import ru.taximaxim.pgsqlblocks.process.ProcessTreeLabelProvider;
import ru.taximaxim.pgsqlblocks.ui.*;
import ru.taximaxim.pgsqlblocks.utils.FilterProcess;
import ru.taximaxim.pgsqlblocks.utils.Images;
import ru.taximaxim.pgsqlblocks.utils.PathBuilder;
import ru.taximaxim.pgsqlblocks.utils.Settings;

import java.io.IOException;
import java.net.URL;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.List;
import java.util.concurrent.*;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;


public class MainForm extends ApplicationWindow implements IUpdateListener {

    private static final Logger LOG = Logger.getLogger(MainForm.class);
    
    private static final String APP_NAME = "pgSqlBlocks";
    private static final String SORT_DIRECTION = "sortDirection";
    private static final String PID = " pid=";
    private static final int ZERO_MARGIN = 0;
    private static final int[] VERTICAL_WEIGHTS = new int[] {80, 20};
    private static final int[] HORIZONTAL_WEIGHTS = new int[] {12, 88};
    private static final int SASH_WIDTH = 2;
    // some problem with 512px: (SWT:4175): Gdk-WARNING **: gdk_window_set_icon_list: icons too large
    private static final int[] ICON_SIZES = { 32, 48, 256/*, 512*/ };
    
    private static final int TRAY_NOTIFICATION_MAX_LENGTH = 4;
    private static Display display;
    private static Tray tray;

    private volatile DbcData selectedDbcData;
    private Process selectedProcess;
    private Text procText;
    private SashForm caTreeSf;
    private TableViewer caServersTable;
    private TreeViewer caMainTree;
    private Composite procComposite;
    private TableViewer bhServersTable;
    private TreeViewer bhMainTree;
    private Action addDb;
    private Action deleteDB;
    private Action editDB;
    private Action connectDB;
    private Action disconnectDB;
    private Action update;
    private Action autoUpdate;
    private Action cancelUpdate;
    private Action onlyBlocked;
    private ToolItem cancelProc;
    private ToolItem terminateProc;
    private TrayItem trayItem;
    private ToolTip tip;
    private static SortColumn sortColumn = SortColumn.BLOCKED_COUNT;
    private static SortDirection sortDirection = SortDirection.UP;
    private Settings settings = Settings.getInstance();
    private FilterProcess filterProcess = FilterProcess.getInstance();
    private final DbcDataListBuilder dbcDataBuilder = DbcDataListBuilder.getInstance(this);
    private ConcurrentMap<String, Image> imagesMap = new ConcurrentHashMap<>();
    private MenuManager serversTableMenuMgr = new MenuManager();
    private boolean hasBlocks = false;

    private int[] caMainTreeColsSize = new int[]{80, 110, 150, 110, 110, 110, 145, 145, 145, 55, 145, 70, 70, 70, 150, 80};
    private String[] caMainTreeColsName = new String[]{
            "pid", "blocked_count", "application_name", "datname", "usename", "client", "backend_start", "query_start",
            "xact_stat", "state", "state_change", "blocked", "locktype", "relation", "query" , "slowquery"};
    
    private String[] caColName = {"PID", "BLOCKED_COUNT", "APPLICATION_NAME", "DATNAME", "USENAME", "CLIENT", "BACKEND_START", "QUERY_START",
            "XACT_STAT", "STATE", "STATE_CHANGE", "BLOCKED", "LOCKTYPE", "RELATION", "QUERY", "SLOWQUERY"};

    public static void main(String[] args) {
        try {
            MainForm wwin = new MainForm();
            wwin.setBlockOnOpen(true);
            wwin.open();
            display.dispose();
        } catch (Exception e) {
            LOG.error("Произошла ошибка:", e);
        }
    }

    public MainForm() {
        super(null);
        addToolBar(SWT.RIGHT | SWT.FLAT);
    }

    // TODO temporary getter, should not be used outside this class
    public static SortColumn getSortColumn() {
        return sortColumn;
    }
    // TODO temporary getter, should not be used outside this class
    public static SortDirection getSortDirection() {
        return sortDirection;
    }

    @Override
    protected void constrainShellSize() {
        super.constrainShellSize();
        getShell().setMaximized( true );
    }
    
    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(APP_NAME);
        shell.setImages(loadIcons());
    }

    private Image[] loadIcons() {
        Image[] icons = new Image[ICON_SIZES.length];
        for (int i = 0; i < ICON_SIZES.length; ++i) {
            icons[i] = new Image(null,
                    getClass().getClassLoader().getResourceAsStream(MessageFormat.format("images/block-{0}x{0}.png",
                            ICON_SIZES[i])));
        }
        return icons;
    }

    @Override
    protected boolean canHandleShellCloseEvent() {
        if (!MessageDialog.openQuestion(getShell(), "Подтверждение действия",
                "Вы действительно хотите выйти из pgSqlBlocks?")) {
            return false;
        }
        dbcDataBuilder.getDbcDataList().forEach(DbcData::shutdown);
        return super.canHandleShellCloseEvent();
    }

    @Override
    protected Control createContents(Composite parent)
    {
        this.getShell().addShellListener(new ShellAdapter() {
            @Override
            public void shellActivated(ShellEvent e) {
                super.shellActivated(e);
                dbcDataBuilder.getDbcDataList().stream().filter(DbcData::isEnabledAutoConnect).forEach(DbcData::startUpdater);
            }
        });

        Menu menuBar = new Menu(getShell(), SWT.BAR);
        MenuItem helpMenuHeader = new MenuItem(menuBar, SWT.CASCADE);
        helpMenuHeader.setText("&pgSqlBlocks");

        Menu helpMenu = new Menu(getShell(), SWT.DROP_DOWN);
        helpMenuHeader.setMenu(helpMenu);

        MenuItem helpGetHelpItem = new MenuItem(helpMenu, SWT.PUSH);
        helpGetHelpItem.setText("&О приложении");
        helpGetHelpItem.addListener(SWT.Selection, e -> new AboutDlg(getShell()).open());
        getShell().setMenuBar(menuBar);

        MenuItem exitMenuItem = new MenuItem(helpMenu, SWT.PUSH);
        exitMenuItem.setText("&Выход");
        exitMenuItem.addListener(SWT.Selection, e -> getShell().close());
        getShell().setMenuBar(menuBar);

        Composite composite = new Composite(parent, SWT.NONE);
        composite.setLayout(new GridLayout());

        GridLayout gridLayout = new GridLayout();
        gridLayout.marginHeight = ZERO_MARGIN;
        gridLayout.marginWidth = ZERO_MARGIN;
        
        GridData gridData = new GridData(SWT.FILL, SWT.FILL, true, true);
        
        SashForm verticalSf = new SashForm(composite, SWT.VERTICAL);
        {
            verticalSf.setLayout(gridLayout);
            verticalSf.setLayoutData(gridData);
            verticalSf.SASH_WIDTH = SASH_WIDTH;
            verticalSf.setBackground(composite.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
            
            Composite topComposite = new Composite(verticalSf, SWT.NONE);
            topComposite.setLayout(gridLayout);
            
            TabFolder tabPanel = new TabFolder(topComposite, SWT.BORDER);
            {
                tabPanel.setLayoutData(gridData);
                TabItem currentActivityTi = new TabItem(tabPanel, SWT.NONE);
                {
                    currentActivityTi.setText("Текущая активность");
                    SashForm currentActivitySf = new SashForm(tabPanel, SWT.HORIZONTAL);
                    {
                        currentActivitySf.setLayout(gridLayout);
                        currentActivitySf.setLayoutData(gridData);
                        currentActivitySf.SASH_WIDTH = SASH_WIDTH;
                        currentActivitySf.setBackground(topComposite.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
                        
                        caServersTable = new TableViewer(currentActivitySf, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.FULL_SELECTION);
                        {
                            caServersTable.getTable().setHeaderVisible(true);
                            caServersTable.getTable().setLayoutData(gridData);
                            TableViewerColumn tvColumn = new TableViewerColumn(caServersTable, SWT.NONE);
                            tvColumn.getColumn().setText("База данных");
                            tvColumn.getColumn().setWidth(200);
                            caServersTable.setContentProvider(new DbcDataListContentProvider());
                            caServersTable.setLabelProvider(new DbcDataListLabelProvider());
                            caServersTable.setInput(dbcDataBuilder.getDbcDataList());
                        }

                        Menu mainMenu = serversTableMenuMgr.createContextMenu(caServersTable.getControl());
                        serversTableMenuMgr.addMenuListener(manager -> {
                            if (caServersTable.getSelection() instanceof IStructuredSelection) {
                                manager.add(cancelUpdate);
                                manager.add(update);
                                manager.add(connectDB);
                                manager.add(disconnectDB);
                                manager.add(addDb);
                                manager.add(editDB);
                                manager.add(deleteDB);
                            }
                        });
                        serversTableMenuMgr.setRemoveAllWhenShown(true);
                        caServersTable.getControl().setMenu(mainMenu);

                        caTreeSf = new SashForm(currentActivitySf, SWT.VERTICAL);
                        {
                            caMainTree = new TreeViewer(caTreeSf, SWT.VIRTUAL | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.FULL_SELECTION);
                            caMainTree.getTree().setHeaderVisible(true);
                            caMainTree.getTree().setLinesVisible(true);
                            caMainTree.getTree().setLayoutData(gridData);
                            for(int i=0;i<caMainTreeColsName.length;i++) {
                                TreeViewerColumn treeColumn = new TreeViewerColumn(caMainTree, SWT.NONE);
                                treeColumn.getColumn().setText(caMainTreeColsName[i]);
                                treeColumn.getColumn().setWidth(caMainTreeColsSize[i]);
                                treeColumn.getColumn().setData("colName",caColName[i]);
                                treeColumn.getColumn().setData(SORT_DIRECTION, SortDirection.UP);
                            }
                            caMainTree.setContentProvider(new ProcessTreeContentProvider(settings));
                            caMainTree.setLabelProvider(new ProcessTreeLabelProvider());
                            ViewerFilter[] filters = new ViewerFilter[1];
                            filters[0] = new ViewerFilter() {
                                @Override
                                public boolean select(Viewer viewer, Object parentElement, Object element) {
                                    return !(element instanceof Process) || filterProcess.isFiltered((Process) element);
                                }
                            };
                            caMainTree.setFilters(filters);
                            
                            procComposite = new Composite(caTreeSf, SWT.BORDER);
                            {
                                procComposite.setLayout(gridLayout);
                                GridData procCompositeGd = new GridData(SWT.FILL, SWT.FILL, true, true);
                                procComposite.setLayoutData(procCompositeGd);
                                procComposite.setVisible(false);
                                ToolBar pcToolBar = new ToolBar(procComposite, SWT.FLAT | SWT.RIGHT);
                                pcToolBar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
                                terminateProc = new ToolItem(pcToolBar, SWT.PUSH);
                                terminateProc.setText("Уничтожить процесс");
                                terminateProc.addListener(SWT.Selection, event -> {
                                    if (selectedProcess != null) {
                                        terminate(selectedProcess);
                                    }
                                });
                                
                                cancelProc = new ToolItem(pcToolBar, SWT.PUSH);
                                cancelProc.setText("Послать сигнал отмены процесса");
                                cancelProc.addListener(SWT.Selection, event -> {
                                    if (selectedProcess != null) {
                                        cancel(selectedProcess);
                                    }
                                });

                                procText = new Text(procComposite, SWT.MULTI | SWT.BORDER | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL);
                                procText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
                            }
                        }
                        caTreeSf.setWeights(VERTICAL_WEIGHTS);
                    }
                    currentActivitySf.setWeights(HORIZONTAL_WEIGHTS);
                    currentActivityTi.setControl(currentActivitySf);
                }
                
                TabItem blocksHistoryTi = new TabItem(tabPanel, SWT.NONE);
                {
                    blocksHistoryTi.setText("История блокировок");
                    SashForm blocksHistorySf = new SashForm(tabPanel, SWT.HORIZONTAL);
                    {
                        blocksHistorySf.setLayout(gridLayout);
                        blocksHistorySf.setLayoutData(gridData);
                        blocksHistorySf.SASH_WIDTH = SASH_WIDTH;
                        blocksHistorySf.setBackground(topComposite.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));

                        bhServersTable = new TableViewer(blocksHistorySf, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.FULL_SELECTION);
                        {
                            bhServersTable.getTable().setHeaderVisible(true);
                            bhServersTable.getTable().setLinesVisible(true);
                            bhServersTable.getTable().setLayoutData(gridData);
                            TableViewerColumn serversTc = new TableViewerColumn(bhServersTable, SWT.NONE);
                            serversTc.getColumn().setText("База данных");
                            serversTc.getColumn().setWidth(200);
                            bhServersTable.setContentProvider(new DbcDataListContentProvider());
                            bhServersTable.setLabelProvider(new DbcDataListLabelProvider());
                        }

                        bhMainTree = new TreeViewer(blocksHistorySf, SWT.VIRTUAL | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.FULL_SELECTION);
                        {
                            bhMainTree.getTree().setHeaderVisible(true);
                            bhMainTree.getTree().setLinesVisible(true);
                            bhMainTree.getTree().setLayoutData(gridData);
                            for(int i = 0; i < caMainTreeColsName.length; i++) {
                                TreeViewerColumn treeColumn = new TreeViewerColumn(bhMainTree, SWT.NONE);
                                treeColumn.getColumn().setText(caMainTreeColsName[i]);
                                treeColumn.getColumn().setWidth(caMainTreeColsSize[i]);
                            }
                            bhMainTree.setContentProvider(new ProcessTreeContentProvider(settings));
                            bhMainTree.setLabelProvider(new ProcessTreeLabelProvider());
                        }
                    }
                    blocksHistorySf.setWeights(HORIZONTAL_WEIGHTS);
                    blocksHistoryTi.setControl(blocksHistorySf);
                }
            }
            Composite logComposite = new Composite(verticalSf, SWT.NONE);
            {
                logComposite.setLayout(gridLayout);
            }
            verticalSf.setWeights(VERTICAL_WEIGHTS);
            UIAppender uiAppender = new UIAppender(logComposite);
            uiAppender.setThreshold(Level.INFO);
            Logger.getRootLogger().addAppender(uiAppender);
            
            Composite statusBar = new Composite(composite, SWT.NONE);
            {
                statusBar.setLayout(gridLayout);
                statusBar.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
                Label appVersionLabel = new Label(statusBar, SWT.HORIZONTAL);
                appVersionLabel.setText("pgSqlBlocks v." + getAppVersion());
            }
        }

        caMainTree.addSelectionChangedListener(event -> {
            if (!caMainTree.getSelection().isEmpty()) {
                IStructuredSelection selected = (IStructuredSelection) event.getSelection();
                selectedProcess = (Process) selected.getFirstElement();
                if(!procComposite.isVisible()) {
                    procComposite.setVisible(true);
                    caTreeSf.layout(true, true);
                }
                procText.setText(String.format("pid=%s%n%s", selectedProcess.getPid(), selectedProcess.getQuery()));
            }
        });
        
        caServersTable.addSelectionChangedListener(event -> {
            if (!caServersTable.getSelection().isEmpty()) {
                IStructuredSelection selected = (IStructuredSelection) event.getSelection();
                DbcData newSelection = (DbcData) selected.getFirstElement();
                if (selectedDbcData != newSelection){
                    selectedDbcData = newSelection;
                    if(procComposite.isVisible()) {
                        procComposite.setVisible(false);
                        caTreeSf.layout(false, false);
                    }
                    serversToolBarState();
                    caMainTree.setInput(selectedDbcData.getProcess());
                    updateUi();
                }
            }
        });
        
        for (TreeColumn column : caMainTree.getTree().getColumns()) {
            column.addListener(SWT.Selection, event -> {
                if (selectedDbcData != null) {
                    caMainTree.getTree().setSortColumn(column);
                    column.setData(SORT_DIRECTION, ((SortDirection)column.getData(SORT_DIRECTION)).getOpposite());
                    sortDirection = (SortDirection)column.getData(SORT_DIRECTION);
                    caMainTree.getTree().setSortDirection(sortDirection.getSwtData());
                    sortColumn = SortColumn.valueOf((String)column.getData("colName"));
                    selectedDbcData.getProcessTree(false);
                    updateUi();
                }
            });
        }

        caServersTable.addDoubleClickListener(event -> {
            if (!caServersTable.getSelection().isEmpty()) {
                IStructuredSelection selected = (IStructuredSelection) event.getSelection();
                selectedDbcData = (DbcData) selected.getFirstElement();
                if (selectedDbcData.getStatus() == DbcStatus.CONNECTED) {
                    dbcDataDisconnect();
                } else {
                    dbcDataConnect();
                }
            }
        });
        display = Display.getCurrent();
        tray = display.getSystemTray();

        if (getSupportsTray()) {
            trayItem = new TrayItem(tray, SWT.NONE);
            trayItem.setImage(getIconImage());
            trayItem.setToolTipText("pgSqlBlocks v." + getAppVersion());
            final Menu trayMenu = new Menu(getShell(), SWT.POP_UP);
            MenuItem trayMenuItem = new MenuItem(trayMenu, SWT.PUSH);
            trayMenuItem.setText("Выход");
            trayMenuItem.addListener(SWT.Selection, event -> getShell().close());
            trayItem.addListener(SWT.MenuDetect, event -> trayMenu.setVisible(true));

            tip = new ToolTip(getShell(), SWT.BALLOON | SWT.ICON_WARNING);
            tip.setText("pgSqlBlocks v." + getAppVersion());
            tip.setAutoHide(true);
            tip.setVisible(false);
            trayItem.setToolTip(tip);
        } else {
            LOG.warn("The system tray is not available");
        }

        return parent;
    }

    private boolean getSupportsTray() {
        return tray != null;
    }

    private Image getIconImage() {
        if (dbcDataBuilder.getDbcDataList().stream().anyMatch(DbcData::hasBlockedProcess)) {
            return getImage(Images.BLOCKED);
        }
        return getImage(Images.UNBLOCKED);
    }

    protected ToolBarManager createToolBarManager(int style) {
        ToolBarManager toolBarManager = new ToolBarManager(style);

        addDb = new Action(Images.ADD_DATABASE.getDescription(),
                ImageDescriptor.createFromImage(getImage(Images.ADD_DATABASE))) {
            
            @Override
            public void run() {
                AddDbcDataDlg addDbcDlg = new AddDbcDataDlg(getShell(), null, dbcDataBuilder.getDbcDataList());
                if (Window.OK == addDbcDlg.open()) {
                    selectedDbcData = addDbcDlg.getNewDbcData();
                    if (selectedDbcData != null) {
                        dbcDataBuilder.add(selectedDbcData);
                        caServersTable.getTable().setSelection(dbcDataBuilder.getDbcDataList().indexOf(selectedDbcData));
                    }
                    serversToolBarState();
                    updateUi();
                }
            }
        };

        toolBarManager.add(addDb);

        deleteDB = new Action(Images.DELETE_DATABASE.getDescription(),
                ImageDescriptor.createFromImage(getImage(Images.DELETE_DATABASE))) {
            @Override
            public void run() {
                if (MessageDialog.openQuestion(getShell(),
                        "Подтверждение действия",
                        String.format("Вы действительно хотите удалить %s?", selectedDbcData.getName()))) {
                    dbcDataBuilder.delete(selectedDbcData);
                    selectedDbcData = null;
                    caMainTree.setInput(null);
                    updateUi();
                }
            }
        };

        deleteDB.setEnabled(false);
        toolBarManager.add(deleteDB);

        editDB = new Action(Images.EDIT_DATABASE.getDescription(),
                ImageDescriptor.createFromImage(getImage(Images.EDIT_DATABASE))) {

            @Override
            public void run() {
                AddDbcDataDlg editDbcDlg = new AddDbcDataDlg(getShell(), selectedDbcData, dbcDataBuilder.getDbcDataList());
                if (Window.OK == editDbcDlg.open()) {
                    DbcData oldOne = editDbcDlg.getEditedDbcData();
                    DbcData newOne = editDbcDlg.getNewDbcData();

                    dbcDataBuilder.edit(oldOne, newOne);
                    updateUi();
                }
            }
        };

        editDB.setEnabled(false);
        toolBarManager.add(editDB);

        toolBarManager.add(new Separator());

        connectDB = new Action(Images.CONNECT_DATABASE.getDescription(),
                ImageDescriptor.createFromImage(getImage(Images.CONNECT_DATABASE))) {
            
            @Override
            public void run() {
                dbcDataConnect();
            }
        };

        connectDB.setEnabled(false);
        toolBarManager.add(connectDB);

        disconnectDB = new Action(Images.DISCONNECT_DATABASE.getDescription(),
                ImageDescriptor.createFromImage(getImage(Images.DISCONNECT_DATABASE))) {
            
            @Override
            public void run() {
                dbcDataDisconnect();
            }
        };

        disconnectDB.setEnabled(false);
        toolBarManager.add(disconnectDB);

        toolBarManager.add(new Separator());

        update =  new Action(Images.UPDATE.getDescription(),
                ImageDescriptor.createFromImage(getImage(Images.UPDATE))) {
            
            @Override
            public void run() {
                if (selectedDbcData != null) {
                    selectedDbcData.startUpdater();
                }
            }
        };

        toolBarManager.add(update);

        autoUpdate = new Action(Images.AUTOUPDATE.getDescription(),
                ImageDescriptor.createFromImage(getImage(Images.AUTOUPDATE))) {
            
            @Override
            public void run() {
                settings.setAutoUpdate(autoUpdate.isChecked());
                if (autoUpdate.isChecked()) {
                    dbcDataBuilder.getDbcDataList().stream()
                            .filter(x -> x.isConnected() || x.isEnabledAutoConnect())
                            .filter(x -> x.getStatus() != DbcStatus.CONNECTION_ERROR)
                            .forEach(DbcData::startUpdater);
                } else {
                    dbcDataBuilder.getDbcDataList().forEach(DbcData::stopUpdater);
                }
            }
        };

        autoUpdate.setChecked(settings.isAutoUpdate());
        toolBarManager.add(autoUpdate);

        cancelUpdate = new Action(Images.CANCEL_UPDATE.getDescription(),
                ImageDescriptor.createFromImage(getImage(Images.CANCEL_UPDATE))) {

            @Override
            public void run() {
                throw new RuntimeException("Not implemented");
/*                if (selectedDbcData != null && selectedDbcData.isConnected()) {
                    // TODO dbcDataBuilder.removeOnceScheduledUpdater(selectedDbcData.);
                    // TODO selectedDbcData.setStatus(DbcStatus.CONNECTED); - wny?
                }*/
            }
        };

        cancelUpdate.setEnabled(false);

        toolBarManager.add(new Separator());

        Action filterSetting = new Action(Images.FILTER.getDescription(),
                ImageDescriptor.createFromImage(getImage(Images.FILTER))) {

            @Override
            public void run() {
                FilterDlg filterDlg = new FilterDlg(getShell(), filterProcess);
                filterDlg.open();
                updateUi();
            }
        };
        
        toolBarManager.add(filterSetting);

        onlyBlocked = new Action(Images.VIEW_ONLY_BLOCKED.getDescription(),
                ImageDescriptor.createFromImage(getImage(Images.VIEW_ONLY_BLOCKED))) {
            
            @Override
            public void run() {
                settings.setOnlyBlocked(onlyBlocked.isChecked());
                updateUi();
            }
        };

        onlyBlocked.setChecked(settings.isOnlyBlocked());
        toolBarManager.add(onlyBlocked);

        toolBarManager.add(new Separator());

        Action exportBlocks = new Action(Images.EXPORT_BLOCKS.getDescription(),
                ImageDescriptor.createFromImage(getImage(Images.EXPORT_BLOCKS))) {
            
            @Override
            public void run() {
                if (dbcDataBuilder.getDbcDataList().stream()
                        .filter(DbcData::hasBlockedProcess).count() > 0) {
                    
                    BlocksHistory.getInstance().save(dbcDataBuilder.getDbcDataList());
                    LOG.info("Блокировка сохранена...");
                } else {
                    LOG.info("Не найдено блокировок для сохранения");
                }
            }
        };

        toolBarManager.add(exportBlocks);

        Action importBlocks = new Action(Images.IMPORT_BLOCKS.getDescription()) {
            @Override
            public void run() {
                FileDialog dialog = new FileDialog(getShell());
                dialog.setFilterPath(PathBuilder.getInstance().getBlockHistoryDir().toString());
                dialog.setText("Открыть историю блокировок");
                dialog.setFilterExtensions(new String[]{"*.xml"});

                List<DbcData> blockedDbsDataList = BlocksHistory.getInstance().open(dialog.open());
                if (!blockedDbsDataList.isEmpty()) {
                    bhServersTable.setInput(blockedDbsDataList);
                    bhMainTree.setInput(blockedDbsDataList.get(0).getProcess());
                    bhMainTree.refresh();
                    bhServersTable.refresh();
                }
            }
        };

        importBlocks.setImageDescriptor(ImageDescriptor.createFromImage(getImage(Images.IMPORT_BLOCKS)));
        toolBarManager.add(importBlocks);

        toolBarManager.add(new Separator());
        
        Action settingsAction = new Action(Images.SETTINGS.getDescription(),
                ImageDescriptor.createFromImage(getImage(Images.SETTINGS))) {
            
            @Override
            public void run() {
                SettingsDlg settingsDlg = new SettingsDlg(getShell(), settings);
                if (Window.OK == settingsDlg.open()) {
                    updateUi();
                    dbcDataBuilder.getDbcDataList().forEach(DbcData::stopUpdater);

                    dbcDataBuilder.getDbcDataList().stream()
                            .filter(x -> x.isConnected() || x.isEnabledAutoConnect())
                            //.filter(x -> x.getStatus() != DbcStatus.CONNECTION_ERROR) // ok, update those too for now
                            .forEach(DbcData::startUpdater);
                }
            }
        };

        toolBarManager.add(settingsAction);
        
        return toolBarManager;
    }

    private void checkBlocks() {
        if (getSupportsTray() && settings.getShowToolTip()) {
            List<String> blockedDB = dbcDataBuilder.getDbcDataList().stream()
                    .filter(DbcData::isConnected)
                    .filter(DbcData::hasBlockedProcess)
                    .limit(TRAY_NOTIFICATION_MAX_LENGTH)
                    .map(DbcData::getName)
                    .collect(Collectors.toList());

            boolean newHasBlocks = !blockedDB.isEmpty();

            if (newHasBlocks && !hasBlocks) {
                String message = String.format("В %s БД имеется блокировка: %s",
                        blockedDB.size() == 1 ? "одной" : "нескольких",
                        String.join(", \n", blockedDB));
                tip.setMessage(message);
                tip.setVisible(true);
            }
            hasBlocks = newHasBlocks;
        }
    }

    private Image getImage(Images type) {
        return imagesMap.computeIfAbsent(type.toString(),
                k -> new Image(null, getClass().getClassLoader().getResourceAsStream(type.getImageAddr())));
    }
    
    private String getAppVersion() {
        URL manifestPath = MainForm.class.getClassLoader().getResource("META-INF/MANIFEST.MF");
        Manifest manifest = null;
        try {
            manifest = new Manifest(manifestPath != null ? manifestPath.openStream() : null);
        } catch (IOException e) {
            LOG.error("Ошибка при чтении манифеста", e);
        }
        Attributes manifestAttributes = manifest != null ? manifest.getMainAttributes() : null;
        String appVersion = manifestAttributes != null ? manifestAttributes.getValue("Implementation-Version") : null;
        if(appVersion == null) {
            return "";
        }
        return appVersion;
    }
    
    private void terminate(Process process) {
        String term = "select pg_terminate_backend(?);";
        boolean kill = false;
        int pid = process.getPid();
        try (PreparedStatement termPs = selectedDbcData.getConnection().prepareStatement(term)) {
            termPs.setInt(1, pid);
            try (ResultSet resultSet = termPs.executeQuery()) {
                if (resultSet.next()) {
                    kill = resultSet.getBoolean(1);
                }
            }
        } catch (SQLException e) {
            LOG.error(selectedDbcData.getName() + " " + e.getMessage(), e);
        }
        if(kill) {
            LOG.info(selectedDbcData.getName() + PID + pid + " is terminated.");
            selectedDbcData.startUpdater();
        } else {
            LOG.info(selectedDbcData.getName() + " failed to terminate " + PID + pid);
        }
    }

    private void cancel(Process process) {
        String cancel = "select pg_cancel_backend(?);";
        int pid = process.getPid();
        boolean kill = false;
        try (PreparedStatement cancelPs = selectedDbcData.getConnection().prepareStatement(cancel)) {
            cancelPs.setInt(1, pid);
            try (ResultSet resultSet = cancelPs.executeQuery()) {
                if (resultSet.next()) {
                    kill = resultSet.getBoolean(1);
                }
            }
        } catch (SQLException e) {
            LOG.error(selectedDbcData.getName() + " " + e.getMessage(), e);
        }
        if(kill) {
            LOG.info(selectedDbcData.getName() + PID + pid + " is canceled.");
            selectedDbcData.startUpdater();
        } else {
            LOG.info(selectedDbcData.getName() + " failed to cancel " + PID + pid);
        }
    }

    private void dbcDataConnect() {
        synchronized (selectedDbcData) {
            caMainTree.setInput(selectedDbcData.getProcess());
            selectedDbcData.startUpdater();
            connectState();
        }
    }
    
    private void dbcDataDisconnect() {
        synchronized (selectedDbcData) {
            LOG.debug(MessageFormat.format("Remove dbcData on disconnect \"{0}\" from updaterList",
                    selectedDbcData.getName()));
            selectedDbcData.disconnect();
            selectedDbcData.stopUpdater();
            disconnectState();
        }
        updateUi();
    }
    
    private void serversToolBarState() {
        if (selectedDbcData != null &&
                (selectedDbcData.getStatus() == DbcStatus.CONNECTION_ERROR ||
                selectedDbcData.getStatus() == DbcStatus.DISABLED)) {
            
            disconnectState();
        } else {
            connectState();
        }
    }
    
    private void connectState() {
        deleteDB.setEnabled(false);
        editDB.setEnabled(false);
        connectDB.setEnabled(false);
        disconnectDB.setEnabled(true);
        cancelUpdate.setEnabled(true);
        cancelProc.setEnabled(true);
        terminateProc.setEnabled(true);
    }
    
    private void disconnectState() {
        deleteDB.setEnabled(true);
        editDB.setEnabled(true);
        connectDB.setEnabled(true);
        disconnectDB.setEnabled(false);
        cancelUpdate.setEnabled(false);
        cancelProc.setEnabled(false);
        terminateProc.setEnabled(false);
    }

    private void updateUi() {
        display.asyncExec(() -> {
            if (!display.isDisposed()) {
                caServersTable.refresh();
                serversToolBarState();
                caMainTree.refresh();
                bhMainTree.refresh();
            }
            trayItem.setImage(getIconImage());
            checkBlocks();
        });
    }

    @Override
    public void serverUpdated() {
        updateUi();
    }
}
