package org.metalscraps.eso.lang.lib.config;

import lombok.Data;
import lombok.experimental.Accessors;
import org.metalscraps.eso.lang.lib.bean.PO;

import java.io.File;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Created by 안병길 on 2018-01-20.
 * Whya5448@gmail.com
 */

@Data
@Accessors(chain = true)
public class SourceToMapConfig {

	private Path path = null;

	public File getFile() {
		return path.toFile();
	}

	public SourceToMapConfig setFile(File file) {
		this.path = file.toPath();
		return this;
	}

	public Path getPath() {
		return path;
	}

	public SourceToMapConfig setPath(Path path) {
		this.path = path;
		return this;
	}

	private int keyGroup = 2;
	private boolean
			processText = true,
			processItemName = true,
			addFileNameToTitle = false,
			toLowerCase = false,
			removeComment;
	private String prefix, suffix;
	private Pattern pattern = null;
	private PO.POWrapType poWrapType = PO.POWrapType.WRAP_ALL;
}
