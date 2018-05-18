package krasa.editorGroups.model;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import krasa.editorGroups.support.Utils;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class EditorGroupIndexValue implements EditorGroup {

	private String ownerPath = "";
	private String title = "";
	private List<String> relatedPaths = new ArrayList<>();

	private transient List<String> links;
	private transient boolean valid = true;

	public EditorGroupIndexValue() {
	}

	public EditorGroupIndexValue(String ownerPath, String title, boolean valid) {
		this.ownerPath = ownerPath;
		this.title = title;
		this.valid = valid;
	}

	public EditorGroupIndexValue setTitle(String title) {
		this.title = StringUtil.notNullize(title);
		return this;
	}

	@NotNull
	public String getOwnerPath() {
		return ownerPath;
	}

	public void setOwnerPath(String ownerPath) {
		this.ownerPath = ownerPath;
	}

	public List<String> getRelatedPaths() {
		return relatedPaths;
	}

	public String getTitle() {
		return title;
	}

	@Override
	public boolean valid() {
		return valid;
	}

	public EditorGroupIndexValue invalidate() {
		this.valid = false;
		return this;
	}

	@Override
	public int size() {
		if (links == null) {
			links = getLinks();
		}
		return links.size();
	}

	@Override
	public VirtualFile getOwnerVirtualFile() {
		if (ownerPath == null) {
			return null;
		}
		return Utils.getFileByPath(ownerPath);
	}

	@Override
	public boolean contains(String canonicalPath) {
		return relatedPaths.contains(canonicalPath);
	}

	@Override
	public List<String> getLinks() {
		if (links == null) {
			ArrayList<String> objects = new ArrayList<>(relatedPaths.size() + 1);
			objects.add(ownerPath);
			objects.addAll(relatedPaths);
			links = objects.stream().distinct().collect(Collectors.toList());
		}

		return links;
	}

	public EditorGroupIndexValue addRelated(String value) {
		relatedPaths.add(value);
		return this;
	}

	@Override
	public boolean isOwner(@NotNull String canonicalPath) {
		return ownerPath.equals(canonicalPath);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		EditorGroupIndexValue that = (EditorGroupIndexValue) o;

		if (!ownerPath.equals(that.ownerPath)) return false;
		if (!title.equals(that.title)) return false;
		return relatedPaths.equals(that.relatedPaths);
	}

	@Override
	public int hashCode() {
		int result = ownerPath.hashCode();
		result = 31 * result + title.hashCode();
		result = 31 * result + relatedPaths.hashCode();
		return result;
	}

	@Override
	public String toString() {
		return "EditorGroupIndexValue{" +
			"title='" + title + '\'' +
			"owner='" + ownerPath + '\'' +
			"related='" + relatedPaths + '\'' +
			'}';
	}
}