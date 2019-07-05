package krasa.editorGroups;

import com.intellij.ide.ui.UISettings;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.fileEditor.impl.EditorWindowHolder;
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl;
import com.intellij.openapi.fileEditor.impl.text.TextEditorImpl;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.util.ui.UIUtil;
import krasa.editorGroups.model.*;
import krasa.editorGroups.support.Notifications;
import krasa.editorGroups.tabs.impl.JBEditorTabs;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class EditorGroupManager {
	private static final Logger LOG = com.intellij.openapi.diagnostic.Logger.getInstance(EditorGroupManager.class);
	public static final Comparator<EditorGroup> COMPARATOR = new Comparator<EditorGroup>() {
		@Override
		public int compare(EditorGroup o1, EditorGroup o2) {
			return o1.getTitle().toLowerCase().compareTo(o2.getTitle().toLowerCase());
		}
	};

	private final Project project;
	//	@NotNull
//	private EditorGroup currentGroup = EditorGroup.EMPTY;
	public IndexCache cache;

	private ApplicationConfigurationComponent config = ApplicationConfigurationComponent.getInstance();
	private PanelRefresher panelRefresher;
	private IdeFocusManager ideFocusManager;
	private boolean warningShown;
	private ExternalGroupProvider externalGroupProvider;
	private AutoGroupProvider autogroupProvider;
	private Key<?> initial_editor_index;
	private volatile SwitchRequest switchRequest;
	public volatile boolean switching = false;

	public EditorGroupManager(Project project, PanelRefresher panelRefresher, IdeFocusManager ideFocusManager, ExternalGroupProvider externalGroupProvider, AutoGroupProvider autogroupProvider, IndexCache cache) {
		this.cache = cache;
		this.panelRefresher = panelRefresher;
		this.ideFocusManager = ideFocusManager;
		this.project = project;
		this.externalGroupProvider = externalGroupProvider;
		this.autogroupProvider = autogroupProvider;
	}


	public static EditorGroupManager getInstance(@NotNull Project project) {
		return ServiceManager.getService(project, EditorGroupManager.class);
	}


	/**
	 * Index throws exceptions, nothing we can do about it here, let the caller try it again later
	 */
	@NotNull
	EditorGroup getGroup(Project project, FileEditor fileEditor, @NotNull EditorGroup displayedGroup, @Nullable EditorGroup requestedGroup, boolean refresh, @NotNull VirtualFile currentFile) throws IndexNotReady {
		if (LOG.isDebugEnabled())
			LOG.debug(">getGroup project = [" + project + "], fileEditor = [" + fileEditor + "], displayedGroup = [" + displayedGroup + "], requestedGroup = [" + requestedGroup + "], force = [" + refresh + "]");

		long start = System.currentTimeMillis();

		EditorGroup result = EditorGroup.EMPTY;
		try {
			if (requestedGroup == null) {
				requestedGroup = displayedGroup;
			}


			String currentFilePath = currentFile.getPath();


			boolean force = refresh && ApplicationConfiguration.state().isForceSwitch();
			if (force && !(requestedGroup instanceof FavoritesGroup) && !(requestedGroup instanceof BookmarkGroup)) {
				if (result.isInvalid()) {
					result = cache.getOwningOrSingleGroup(currentFilePath);
				}
				if (result.isInvalid()) {
					result = cache.getLastEditorGroup(currentFilePath, false, true);
				}
				if (result.isInvalid()) {
					result = AutoGroupProvider.getInstance(project).findFirstMatchingRegexGroup(currentFile);
				}
			}

			if (result.isInvalid()) {
				cache.validate(requestedGroup);
				if (requestedGroup.isValid()
					&& (requestedGroup instanceof AutoGroup || requestedGroup.containsLink(project, currentFilePath) || requestedGroup.isOwner(currentFilePath))) {
					result = requestedGroup;
				}
			}

			if (!force) {
				if (result.isInvalid()) {
					result = cache.getOwningOrSingleGroup(currentFilePath);
				}

				if (result.isInvalid()) {
					result = cache.getLastEditorGroup(currentFilePath, true, true);
				}
			}

			if (result.isInvalid()) {
				if (config.getState().isSelectRegexGroup()) {
					result = AutoGroupProvider.getInstance(project).findFirstMatchingRegexGroup(currentFile);
				} else if (config.getState().isAutoSameName()) {
					result = AutoGroup.SAME_NAME_INSTANCE;
				} else if (config.getState().isAutoFolders()) {
					result = AutoGroup.DIRECTORY_INSTANCE;
				}
			}

			if (refresh || (result instanceof AutoGroup && result.size(project) == 0)) {
				if (LOG.isDebugEnabled()) {
					LOG.debug("refreshing result");
				}
				//refresh
				if (result == requestedGroup && result instanceof EditorGroupIndexValue) { // force loads new one from index
					cache.initGroup((EditorGroupIndexValue) result);
				} else if (result instanceof SameNameGroup) {
					result = autogroupProvider.getSameNameGroup(currentFile);
				} else if (result instanceof RegexGroup) {
					result = autogroupProvider.getRegexGroup((RegexGroup) result, project, currentFile);
				} else if (result instanceof FolderGroup) {
					result = autogroupProvider.getFolderGroup(currentFile);
				} else if (result instanceof FavoritesGroup) {
					result = externalGroupProvider.getFavoritesGroup(result.getTitle());
				} else if (result instanceof BookmarkGroup) {
					result = externalGroupProvider.getBookmarkGroup();
				}


				if (result instanceof SameNameGroup && result.size(project) <= 1 && !(requestedGroup instanceof SameNameGroup)) {
					EditorGroup slaveGroup = cache.getSlaveGroup(currentFilePath);
					if (slaveGroup.isValid()) {
						result = slaveGroup;
					} else if (config.getState().isAutoFolders()
						&& !AutoGroup.SAME_FILE_NAME.equals(cache.getLast(currentFilePath))) {
						result = autogroupProvider.getFolderGroup(currentFile);
					}
				}
			}

//		if (result instanceof AutoGroup) {
//			result = cache.updateGroups((AutoGroup) result, currentFilePath);
//		}


			if (LOG.isDebugEnabled())
				LOG.debug("< getGroup " + (System.currentTimeMillis() - start) + "ms, file=" + currentFile.getName() + " title='" + result.getTitle() + "' " + result);
			cache.setLast(currentFilePath, result);
		} catch (IndexNotReadyException | ProcessCanceledException e) {
			throw new IndexNotReady(">getGroup project = [" + project + "], fileEditor = [" + fileEditor + "], displayedGroup = [" + displayedGroup + "], requestedGroup = [" + requestedGroup + "], force = [" + refresh + "]", e);
		}
		return result;
	}

	public void switching(SwitchRequest switchRequest) {
		this.switchRequest = switchRequest;
		switching = true;
		if (LOG.isDebugEnabled())
			LOG.debug("switching " + "switching = [" + switching + "], group = [" + switchRequest.getGroup() + "], fileToOpen = [" + switchRequest.getFileToOpen() + "], myScrollOffset = [" + switchRequest.getMyScrollOffset() + "]");
	}

	public void enableSwitching() {
		SwingUtilities.invokeLater(() -> {
			ideFocusManager.doWhenFocusSettlesDown(() -> {
				if (LOG.isDebugEnabled()) LOG.debug("enabling switching");
				this.switching = false;
			});
		});
	}


	@Nullable
	public SwitchRequest getAndClearSwitchingRequest(@NotNull VirtualFile file) {
		VirtualFile switchingFile;
		if (switchRequest == null) {
			switchingFile = null;
		} else {
			switchingFile = switchRequest.fileToOpen;
		}


		if (file.equals(switchingFile)) {
			SwitchRequest switchingGroup = switchRequest;
			clearSwitchingRequest();
			if (LOG.isDebugEnabled()) {
				LOG.debug("<getSwitchingRequest " + switchingGroup);
			}
			return switchingGroup;
		}
		if (LOG.isDebugEnabled())
			LOG.debug("<getSwitchingRequest=null  " + "file = [" + file + "], switchingFile=" + switchingFile);
		return null;
	}


	public boolean isSwitching() {
		if (LOG.isDebugEnabled()) {
			LOG.debug("isSwitching switchRequest=" + switchRequest + ", switching=" + switching);
		}
		return switchRequest != null || switching;
	}

	public List<EditorGroup> getGroups(VirtualFile file) {
		List<EditorGroup> groups = cache.findGroups(file.getPath());
		groups.sort(COMPARATOR);
		return groups;
	}

	//TODO cache it?
	public List<EditorGroupIndexValue> getAllGroups() throws IndexNotReadyException {
		long start = System.currentTimeMillis();
		List<EditorGroupIndexValue> allGroups = cache.getAllGroups();
		allGroups.sort(COMPARATOR);
		if (LOG.isDebugEnabled()) LOG.debug("getAllGroups " + (System.currentTimeMillis() - start));
		return allGroups;
	}

	public void initCache() {
		panelRefresher.initCache();
	}

	public Color getColor(VirtualFile file) {
		String canonicalPath = file.getPath();
		EditorGroup group = cache.getEditorGroupForColor(canonicalPath);
		if (group != null) {
			return group.getBgColor();
		}
		return null;
	}

	public Color getFgColor(VirtualFile file) {
		String canonicalPath = file.getPath();
		EditorGroup group = cache.getEditorGroupForColor(canonicalPath);
		if (group != null) {
			return group.getFgColor();
		}
		return null;
	}

	public Result open(EditorGroupPanel groupPanel, VirtualFile fileToOpen, Integer line, boolean newWindow, boolean newTab, Splitters split) {
		EditorGroup displayedGroup = groupPanel.getDisplayedGroup();
		JBEditorTabs tabs = groupPanel.getTabs();

		EditorWindowHolder parentOfType = UIUtil.getParentOfType(EditorWindowHolder.class, groupPanel);
		EditorWindow currentWindow = null;
		if (parentOfType != null) {
			currentWindow = parentOfType.getEditorWindow();
		}

		return open2(currentWindow, groupPanel.getFile(), fileToOpen, line, displayedGroup, newWindow, newTab, split, new SwitchRequest(displayedGroup, fileToOpen, tabs.getMyScrollOffset(), tabs.getWidth(), line));
	}

	public Result open(VirtualFile virtualFileByAbsolutePath, boolean window, boolean tab, Splitters split, EditorGroup group, VirtualFile current) {
		return open2(null, current, virtualFileByAbsolutePath, null, group, window, tab, split, new SwitchRequest(group, virtualFileByAbsolutePath));
	}

	private Result open2(EditorWindow currentWindowParam, @Nullable VirtualFile currentFile, VirtualFile fileToOpen, Integer line, EditorGroup group, boolean newWindow, boolean newTab, Splitters split, SwitchRequest switchRequest) {
		if (LOG.isDebugEnabled())
			LOG.debug("open2 fileToOpen = [" + fileToOpen + "], currentFile = [" + currentFile + "], group = [" + group + "], newWindow = [" + newWindow + "], newTab = [" + newTab + "], split = [" + split + "], switchingRequest = [" + switchRequest + "]");
		AtomicReference<Result> resultAtomicReference = new AtomicReference<Result>();
		switching(switchRequest);

		if (!warningShown && UISettings.getInstance().getReuseNotModifiedTabs()) {
			Notifications.notifyBugs();
			warningShown = true;
		}


		if (initial_editor_index == null) {
			//TODO it does not work in constructor 
			try {
				//noinspection deprecation
				initial_editor_index = Key.findKeyByName("initial editor index");
			} catch (Exception e) {
				LOG.error(e);
				initial_editor_index = Key.create("initial editor index not found");
			}
		}


		CommandProcessor.getInstance().executeCommand(project, () -> {
			final FileEditorManagerImpl manager = (FileEditorManagerImpl) FileEditorManagerEx.getInstance(project);

			VirtualFile selectedFile = currentFile;
			EditorWindow currentWindow = currentWindowParam;
			if (currentWindow == null) {
				currentWindow = manager.getCurrentWindow();
			}
			if (selectedFile == null && currentWindow != null) {
				selectedFile = currentWindow.getSelectedFile();
			}

			if (!split.isSplit() && !newWindow && fileToOpen.equals(selectedFile)) {
				FileEditor editors = currentWindow.getManager().getSelectedEditor(fileToOpen);
				boolean scroll = scroll(line, editors);
				if (scroll) {
					resultAtomicReference.set(new Result(true));
				}
				if (LOG.isDebugEnabled()) {
					LOG.debug("fileToOpen.equals(selectedFile) [fileToOpen=" + fileToOpen + ", selectedFile=" + selectedFile + ", currentFile=" + currentFile + "]");
				}
				resetSwitching();
				return;
			}
			fileToOpen.putUserData(EditorGroupPanel.EDITOR_GROUP, group); // for project view colors

			if (initial_editor_index != null) {
				fileToOpen.putUserData(initial_editor_index, null);
			}
			if (split.isSplit() && currentWindow != null) {
				if (LOG.isDebugEnabled()) LOG.debug("openFileInSplit " + fileToOpen);
				EditorWindow split1 = currentWindow.split(split.getOrientation(), true, fileToOpen, true);
				if (split1 == null) {
					LOG.debug("no editors opened");
					resetSwitching();
				}
			} else if (newWindow) {
				if (LOG.isDebugEnabled()) LOG.debug("openFileInNewWindow fileToOpen = " + fileToOpen);
				Pair<FileEditor[], FileEditorProvider[]> pair = manager.openFileInNewWindow(fileToOpen);
				scroll(line, pair.first);
				if (pair.first.length == 0) {
					LOG.debug("no editors opened");
					resetSwitching();
				}
			} else {
				boolean reuseNotModifiedTabs = UISettings.getInstance().getReuseNotModifiedTabs();
//				boolean fileWasAlreadyOpen = currentWindow.isFileOpen(fileToOpen);

				try {
					if (newTab) {
						UISettings.getInstance().setReuseNotModifiedTabs(false);
					}

					if (LOG.isDebugEnabled()) LOG.debug("openFile " + fileToOpen);
					Pair<FileEditor[], FileEditorProvider[]> pair = manager.openFileWithProviders(fileToOpen, true, currentWindow);
					FileEditor[] fileEditors = pair.first;

					if (fileEditors.length == 0) {  //directory or some fail
						Notifications.warning("Unable to open editor for file " + fileToOpen.getName(), null);
						LOG.debug("no editors opened");
						resetSwitching();
						return;
					}
					for (FileEditor fileEditor : fileEditors) {
						if (LOG.isDebugEnabled()) LOG.debug("opened fileEditor = " + fileEditor);
					}
					scroll(line, fileEditors);

					if (reuseNotModifiedTabs  //it is bugged, do no close files - bad workaround -> when switching to an already opened file, the previous tab would not close   
//						&& !fileWasAlreadyOpen  //this mostly works, but not always - sometimes current file gets closed and editor loses focus
					) {
						return;
					}
					//not sure, but it seems to mess order of tabs less if we do it after opening a new tab
					if (selectedFile != null && !newTab) {
						if (LOG.isDebugEnabled()) LOG.debug("closeFile " + selectedFile);
						manager.closeFile(selectedFile, currentWindow, false);
					}
				} finally {
					UISettings.getInstance().setReuseNotModifiedTabs(reuseNotModifiedTabs);
				}
			}


		}, null, null);


		return resultAtomicReference.get();
	}

	private boolean scroll(Integer line, FileEditor... fileEditors) {
		if (line != null) {
			for (FileEditor fileEditor : fileEditors) {
				if (fileEditor instanceof TextEditorImpl) {
					Editor editor = ((TextEditorImpl) fileEditor).getEditor();
					LogicalPosition position = new LogicalPosition(line, 0);
					editor.getCaretModel().removeSecondaryCarets();
					editor.getCaretModel().moveToLogicalPosition(position);
					editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
					editor.getSelectionModel().removeSelection();
					IdeFocusManager.getGlobalInstance().requestFocus(editor.getContentComponent(), true);
					return true;
				}
			}
		}
		return false;
	}

	public void resetSwitching() {
		clearSwitchingRequest();
		enableSwitching();
	}


	private void clearSwitchingRequest() {
		LOG.debug("clearSwitchingRequest");
		switchRequest = null;
	}


	class Result {
		boolean scrolledOnly;

		public Result(boolean scrolledOnly) {
			this.scrolledOnly = scrolledOnly;
		}

		public boolean isScrolledOnly() {
			return scrolledOnly;
		}
	}
}
