/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package net.sourceforge.subsonic.service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import net.sf.ehcache.Ehcache;
import net.sourceforge.subsonic.Logger;
import net.sourceforge.subsonic.dao.UserDao;
import net.sourceforge.subsonic.domain.MediaFolder;
import net.sourceforge.subsonic.domain.User;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authentication.dao.SaltSource;
import org.springframework.security.authentication.encoding.PasswordEncoder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.GrantedAuthorityImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestWrapper;

/**
 * Provides security-related services for authentication and authorization.
 *
 * @author Sindre Mehus
 */
public class SecurityService implements InitializingBean, UserDetailsService {

    private static final Logger LOG = Logger.getLogger(SecurityService.class);

    private UserDao userDao;
    private SettingsService settingsService;
    private MediaFolderService mediaFolderService;
    private Ehcache userCache;

    private PasswordEncoder passwordEncoder;
	private SaltSource saltSource;

	/**
	 * After instantiation, check if this is an old Subsonic instance, still
	 * storing passwords in clear-text. If so, update to salted hashing.
	 */
	public void afterPropertiesSet() throws Exception {
		JdbcTemplate template = userDao.getJdbcTemplate();
		if (template.queryForInt("select count(*) from version where version = 21") == 0) {
            LOG.info("Updating database schema to version 21. (securing user passwords)");
            template.execute("insert into version values (21)");

    		for (User user : getAllUsers()) {
    			setSecurePassword(user);
    			updateUser(user);
    		}
		}
	}
	
	public void setSecurePassword(User user) {
		UserDetails userDetails = new org.springframework.security.core.userdetails.User(
				user.getUsername(), user.getPassword(), new ArrayList<GrantedAuthority>());
		String encodedPassword = passwordEncoder.encodePassword(userDetails.getPassword(),
				saltSource.getSalt(userDetails));
		user.setPassword(encodedPassword);
	}
	
    /**
     * Locates the user based on the username.
     *
     * @param username The username presented to the {@link DaoAuthenticationProvider}
     * @return A fully populated user record (never <code>null</code>)
     * @throws UsernameNotFoundException if the user could not be found or the user has no GrantedAuthority.
     * @throws DataAccessException       If user could not be found for a repository-specific reason.
     */
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException, DataAccessException {
    	LOG.debug("Load user " + username);
    	System.out.println("Load user " + username);
        User user = getUserByName(username);
        if (user == null) {
            throw new UsernameNotFoundException("User \"" + username + "\" was not found.");
        }

        String[] roles = userDao.getRolesForUser(username);
        List<GrantedAuthority> authorities = new ArrayList<GrantedAuthority>(roles.length);
        for (int i = 0; i < roles.length; i++) {
            authorities.add(new GrantedAuthorityImpl("ROLE_" + roles[i].toUpperCase()));
        }

        // If user is LDAP authenticated, disable user. The proper authentication should in that case
        // be done by SubsonicLdapBindAuthenticator.
        boolean enabled = !user.isLdapAuthenticated();

        return new org.springframework.security.core.userdetails.User(
        		username, user.getPassword(), enabled, true, true, true, authorities);
    }

    /**
     * Returns the currently logged-in user for the given HTTP request.
     *
     * @param request The HTTP request.
     * @return The logged-in user, or <code>null</code>.
     */
    public User getCurrentUser(HttpServletRequest request) {
        String username = getCurrentUsername(request);
        return username == null ? null : userDao.getUserByName(username);
    }

    /**
     * Returns the name of the currently logged-in user.
     *
     * @param request The HTTP request.
     * @return The name of the logged-in user, or <code>null</code>.
     */
    public String getCurrentUsername(HttpServletRequest request) {
        return new SecurityContextHolderAwareRequestWrapper(request, null).getRemoteUser();
    }

    /**
     * Returns the user with the given username.
     *
     * @param username The username used when logging in.
     * @return The user, or <code>null</code> if not found.
     */
    public User getUserByName(String username) {
        return userDao.getUserByName(username);
    }

    /**
     * Returns all users.
     *
     * @return Possibly empty array of all users.
     */
    public List<User> getAllUsers() {
        return userDao.getAllUsers();
    }

    /**
     * Creates a new user.
     *
     * @param user The user to create.
     */
    public void createUser(User user) {
        userDao.createUser(user);
        LOG.info("Created user " + user.getUsername());
    }

    /**
     * Deletes the user with the given username.
     *
     * @param username The username.
     */
    public void deleteUser(String username) {
        userDao.deleteUser(username);
        LOG.info("Deleted user " + username);
        userCache.remove(username);
    }

    /**
     * Updates the given user.
     *
     * @param user The user to update.
     */
    public void updateUser(User user) {
        userDao.updateUser(user);
        userCache.remove(user.getUsername());
    }

    /**
     * Updates the byte counts for given user.
     *
     * @param user                 The user to update, may be <code>null</code>.
     * @param bytesStreamedDelta   Increment bytes streamed count with this value.
     * @param bytesDownloadedDelta Increment bytes downloaded count with this value.
     * @param bytesUploadedDelta   Increment bytes uploaded count with this value.
     */
    public void updateUserByteCounts(User user, long bytesStreamedDelta, long bytesDownloadedDelta, long bytesUploadedDelta) {
        if (user == null) {
            return;
        }

        user.setBytesStreamed(user.getBytesStreamed() + bytesStreamedDelta);
        user.setBytesDownloaded(user.getBytesDownloaded() + bytesDownloadedDelta);
        user.setBytesUploaded(user.getBytesUploaded() + bytesUploadedDelta);

        userDao.updateUser(user);
    }

    /**
     * Returns whether the given file may be read.
     *
     * @return Whether the given file may be read.
     */
    public boolean isReadAllowed(File file) {
        // Allowed to read from both music folder, playlist folder and podcast folder.
        return isInMediaFolder(file) || isInPlaylistFolder(file) || isInPodcastFolder(file);
    }

    /**
     * Returns whether the given file may be written, created or deleted.
     *
     * @return Whether the given file may be written, created or deleted.
     */
    public boolean isWriteAllowed(File file) {
        // Only allowed to write playlists, podcasts or cover art.
        boolean isPlaylist = isInPlaylistFolder(file);
        boolean isPodcast = isInPodcastFolder(file);
        boolean isCoverArt = isInMediaFolder(file) && file.getName().startsWith("folder.");

        return isPlaylist || isPodcast || isCoverArt;
    }

    /**
     * Returns whether the given file may be uploaded.
     *
     * @return Whether the given file may be uploaded.
     */
    public boolean isUploadAllowed(File file) {
        return isInMediaFolder(file) && !file.exists();
    }

    /**
     * Returns whether the given file is located in one of the music folders (or any of their sub-folders).
     *
     * @param file The file in question.
     * @return Whether the given file is located in one of the music folders.
     */
    private boolean isInMediaFolder(File file) {
        List<MediaFolder> folders = mediaFolderService.getAllMediaFolders();
        String path = file.getPath();
        for (MediaFolder folder : folders) {
            if (isFileInFolder(path, folder.getPath().getPath())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether the given file is located in the playlist folder (or any of its sub-folders).
     *
     * @param file The file in question.
     * @return Whether the given file is located in the playlist folder.
     */
    private boolean isInPlaylistFolder(File file) {
        String playlistFolder = settingsService.getPlaylistFolder();
        return isFileInFolder(file.getPath(), playlistFolder);
    }

    /**
     * Returns whether the given file is located in the Podcast folder (or any of its sub-folders).
     *
     * @param file The file in question.
     * @return Whether the given file is located in the Podcast folder.
     */
    private boolean isInPodcastFolder(File file) {
        String podcastFolder = settingsService.getPodcastFolder();
        return isFileInFolder(file.getPath(), podcastFolder);
    }

    /**
     * Returns whether the given file is located in the given folder (or any of its sub-folders).
     * If the given file contains the expression ".." (indicating a reference to the parent directory),
     * this method will return <code>false</code>.
     *
     * @param file   The file in question.
     * @param folder The folder in question.
     * @return Whether the given file is located in the given folder.
     */
    protected boolean isFileInFolder(String file, String folder) {
        // Deny access if file contains ".." surrounded by slashes (or end of line).
        if (file.matches(".*(/|\\\\)\\.\\.(/|\\\\|$).*")) {
            return false;
        }

        // Convert slashes.
        file = file.replace('\\', '/');
        folder = folder.replace('\\', '/');

        return file.toUpperCase().startsWith(folder.toUpperCase());
    }

    public void setSettingsService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public void setMediaFolderService(MediaFolderService mediaFolderService) {
		this.mediaFolderService = mediaFolderService;
	}

	public void setUserDao(UserDao userDao) {
        this.userDao = userDao;
    }

    public void setUserCache(Ehcache userCache) {
        this.userCache = userCache;
    }

	public void setPasswordEncoder(PasswordEncoder passwordEncoder) {
		this.passwordEncoder = passwordEncoder;
	}

	public void setSaltSource(SaltSource saltSource) {
		this.saltSource = saltSource;
	}

}