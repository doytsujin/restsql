/* Copyright (c) restSQL Project Contributors. Licensed under MIT. */
package org.restsql.core.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

import org.restsql.core.Config;
import org.restsql.core.Factory;
import org.restsql.core.SqlResource;
import org.restsql.core.SqlResourceException;
import org.restsql.core.Trigger;
import org.restsql.core.Factory.SqlResourceFactory;
import org.restsql.core.Factory.SqlResourceFactoryException;
import org.restsql.core.sqlresource.ObjectFactory;
import org.restsql.core.sqlresource.SqlResourceDefinition;

/**
 * Manages SQL Resource construction.
 * 
 * @author Mark Sawers
 */
public class SqlResourceFactoryImpl implements SqlResourceFactory {
	private final Map<String, SqlResource> sqlResources = new HashMap<String, SqlResource>();
	private String sqlResourcesDir;

	@SuppressWarnings("unchecked")
	@Override
	public SqlResource getSqlResource(final String resName) throws SqlResourceFactoryException,
			SqlResourceException {
		SqlResource sqlResource = sqlResources.get(resName);
		if (sqlResource == null) {
			final InputStream inputStream = getInputStream(resName);
			JAXBContext context;
			try {
				context = JAXBContext.newInstance(ObjectFactory.class);
				final Unmarshaller unmarshaller = context.createUnmarshaller();
				unmarshaller.setSchema(null);
				final SqlResourceDefinition definition = ((JAXBElement<SqlResourceDefinition>) unmarshaller
						.unmarshal(inputStream)).getValue();
				sqlResource = new SqlResourceImpl(resName, definition, Factory.getSqlResourceMetaData(
						resName, definition), Factory.getSqlBuilder(), new ArrayList<Trigger>());
				sqlResources.put(resName, sqlResource);
			} catch (final JAXBException exception) {
				throw new SqlResourceFactoryException("Error unmarshalling SQL Resource "
						+ getSqlResourceFileName(resName) + " -- " + exception.getMessage());
			}
		}
		return sqlResource;
	}

	@Override
	public InputStream getSqlResourceDefinition(final String resName) throws SqlResourceFactoryException {
		return getInputStream(resName);
	}

	/**
	 * Returns available SQL Resource names.
	 */
	@Override
	public List<String> getSqlResourceNames() {
		final List<String> resNames = new ArrayList<String>();
		getSqlResourceNames(resNames, getSqlResourcesDir(), "");
		return resNames;
	}

	/** Returns true if the resource has been loaded, i.e. requested previously. */
	public boolean isSqlResourceLoaded(final String name) {
		return sqlResources.containsKey(name);
	}

	// Private utils

	private InputStream getInputStream(final String resName) throws SqlResourceFactoryException {
		final String fileName = getSqlResourceFileName(resName);
		InputStream inputStream = null;
		try {
			inputStream = new FileInputStream(fileName);
		} catch (final FileNotFoundException exception) {
			inputStream = this.getClass().getResourceAsStream(fileName);
		}
		if (inputStream == null) {
			throw new SqlResourceFactoryException("SQL Resource " + resName + " not found - expected "
					+ fileName);
		}
		return inputStream;
	}

	private String getSqlResourceFileName(final String resName) {
		final StringBuilder fileName = new StringBuilder(128);
		fileName.append(getSqlResourcesDir());
		final StringTokenizer tokenizer = new StringTokenizer(resName, ".");
		while (tokenizer.hasMoreTokens()) {
			fileName.append("/");
			fileName.append(tokenizer.nextToken());
		}
		fileName.append(".xml");
		return fileName.toString();
	}

	/**
	 * Scans for xml files and recursively descends subdirs.
	 */
	private void getSqlResourceNames(final List<String> resNames, final String dirName,
			final String packageName) {
		final File dir = new File(dirName);
		Config.logger.info("listing files for " + dirName);
		for (final File file : dir.listFiles()) {
			if (file.isFile()) {
				final int extIndex = file.getName().indexOf(".xml");
				if (extIndex > 0) {
					resNames.add(packageName + file.getName().substring(0, extIndex));
				}
			}
		}
		for (final File subDir : dir.listFiles()) {
			if (subDir.isDirectory()) {
				String subPackageName = packageName.length() == 0 ? subDir.getName() + "." : packageName
						+ subDir.getName() + ".";
				getSqlResourceNames(resNames, subDir.getAbsolutePath(), subPackageName);
			}
		}
	}

	private String getSqlResourcesDir() {
		if (sqlResourcesDir == null) {
			sqlResourcesDir = Config.properties.getProperty(Config.KEY_SQLRESOURCES_DIR,
					Config.DEFAULT_SQLRESOURCES_DIR);
			Config.logger.info("SqlResources dir is " + sqlResourcesDir);
		}
		return sqlResourcesDir;
	}
}
