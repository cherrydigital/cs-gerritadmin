// Copyright (C) 2012 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.googlesource.gerrit.plugins.gerritadmin;

import com.google.gerrit.common.data.ProjectAccess;
import com.google.gerrit.extensions.annotations.Listen;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.reviewdb.client.*;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountControl;
import com.google.gerrit.server.account.GroupBackend;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.MetaDataUpdate;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.util.TimeUtil;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Collections;
import java.util.List;

@Listen
@Singleton
//public class DefaultOwner implements NewProjectCreatedListener, GroupBackend{
public class GerritAdminHandler implements  LifecycleListener{
	private static final Logger log = LoggerFactory .getLogger(GerritAdminHandler.class);
	private String remote_user = "";
	private Provider<CurrentUser> user;
	private GroupBackend groupBackend;
	private GitRepositoryManager grm;
	private Repository git;
	private Project.NameKey projectName;
	private ProjectAccess prjAccess;
	private AccountCache accountCache;
	private final AccountControl.Factory accountControlFactory;
	private final SchemaFactory<ReviewDb> schemaFactory;
	private final IdentifiedUser.GenericFactory userFactory;
	private final MetaDataUpdate.User metaDataUpdateFactory;
	private final ProjectCache projectCache;

	private static final String UUID_PREFIX = "user:";
	private static final String NAME_PREFIX = "user/";
	private static final String ACCOUNT_PREFIX = "userid/";
	private static final String ACCOUNT_ID_PATTERN = "[1-9][0-9]*";
	private static final int MAX = 10;
	private static final String ADMIN_ID="admin";
	private static final int ADMIN_GROUP_ID=1;

	@Inject
	public GerritAdminHandler(Provider<CurrentUser> user, GitRepositoryManager grm,  AccountCache accountCache,  AccountControl.Factory accountControlFactory,SchemaFactory<ReviewDb> schemaFactory, IdentifiedUser.GenericFactory userFactory, MetaDataUpdate.User metaDataUpdateFactory, GroupBackend groupBackend,ProjectCache projectCache ) {
		this.user = user;
		this.grm = grm;
		this.accountCache = accountCache;
		this.accountControlFactory = accountControlFactory;
		this.schemaFactory = schemaFactory;
		this.userFactory = userFactory;
		this.metaDataUpdateFactory = metaDataUpdateFactory;
		this.groupBackend = groupBackend;
		this.projectCache = projectCache;
	}

	public static String readStream(Reader reader) throws java.io.IOException{
    BufferedReader bReader = new BufferedReader(reader);
    StringBuilder strBuilder = new StringBuilder();
    String line = "";
    while(true) {
      line = bReader.readLine();
      if(line == null){
          break;
      }
      strBuilder.append(line);
      strBuilder.append("\n");
    }
    bReader.close();
    return strBuilder.toString();
  }

  public static String readFileFromFilepath(String filePath) throws java.io.IOException {
      return readStream( new FileReader(filePath) );
  }
	
	private void addUser(String uid, Boolean asAdmin, String pubKey) {
		ReviewDb db = null;
		try {
			 db = schemaFactory.open();

			// check if uid exists
      AccountExternalId.Key usernameKey =  new AccountExternalId.Key( AccountExternalId.SCHEME_USERNAME, uid);
      AccountExternalId.Key gerritKey =  new AccountExternalId.Key( AccountExternalId.SCHEME_GERRIT, uid);

      AccountExternalId externalId = db.accountExternalIds().get(usernameKey);
      if(externalId == null) {
        log.info(uid + " account not found");
        log.info("creating new account id:" + uid);
        Account.Id newId = new Account.Id(db.nextAccountId());
        Account newAccount = new Account(newId, TimeUtil.nowTs());

        newAccount.setFullName(uid);

        AccountExternalId usernameId = new AccountExternalId(newId, usernameKey);
        AccountExternalId gerritId = new AccountExternalId(newId, gerritKey);
        log.info("new account id for " + uid + " id:" + String.valueOf(newId.get()) );

        db.accounts().insert(Collections.singleton(newAccount));
        db.accountExternalIds().insert(Collections.singleton(usernameId));
        db.accountExternalIds().insert(Collections.singleton(gerritId));

        // add SSH key
        if(pubKey != null) {
          AccountSshKey.Id sid = new  AccountSshKey.Id(newId, 1);
          AccountSshKey sshKey = new AccountSshKey(sid, pubKey);
          db.accountSshKeys().insert(Collections.singleton(sshKey));
          log.info("Public Key Inserted for " + uid);
        }
      }
      else {
        log.info(uid + " account found");
      }

      if(asAdmin) {
        boolean isAdmin = false;
        AccountExternalId usernmeExtId = db.accountExternalIds().get(usernameKey);
        Account account = db.accounts().get(usernmeExtId.getAccountId());
        List<AccountGroupMember> groups = db.accountGroupMembers().byAccount(account.getId()).toList();
        for(AccountGroupMember gm: groups) {
          AccountGroup.Id gid = gm.getAccountGroupId();
          if(gid.get() == ADMIN_GROUP_ID) {
            isAdmin = true;
          }
        }

        if(isAdmin) {
          log.info("uid:" + uid + " already Administrators");
        }
        else {
          log.info("uid:" + uid + " adding to Administrators");
          final AccountGroupMember member = new AccountGroupMember(new AccountGroupMember.Key(account.getId(), new AccountGroup.Id(ADMIN_GROUP_ID)));
          db.accountGroupMembersAudit().insert(Collections.singleton( new AccountGroupMemberAudit(member, account.getId(), TimeUtil.nowTs())));
          db.accountGroupMembers().insert(Collections.singleton(member));
          accountCache.evict(account.getId());
          log.info("uid:" + uid + " added to Administrators");
        }
      }
		}
		catch(Exception e) {
			log.error("error:" ,e);
		}
		finally {
			if(db != null) {
				db.close();
			}
		}
	}

	@Override
	public void start() {
		String pubKey = "";
		try {
			String home = System.getenv("HOME");
			String pubKeyPath = home + "/.ssh/id_rsa.pub";
			log.info("Public Key path:" + pubKeyPath);
			pubKey = readFileFromFilepath(pubKeyPath);
		}
		catch(IOException e) {
			log.info("Public Key not found");
		}
		if( pubKey.trim().equals("")) {
			addUser(ADMIN_ID, true, null);
		}
		else {
			addUser(ADMIN_ID, true, pubKey);
		}
	}

	@Override
	public void stop() {
	}
}
