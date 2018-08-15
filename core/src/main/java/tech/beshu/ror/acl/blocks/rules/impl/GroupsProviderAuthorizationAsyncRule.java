/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */

package tech.beshu.ror.acl.blocks.rules.impl;

import com.google.common.collect.Sets;
import tech.beshu.ror.acl.blocks.rules.AsyncAuthorization;
import tech.beshu.ror.acl.definitions.groupsproviders.GroupsProviderServiceClient;
import tech.beshu.ror.acl.definitions.groupsproviders.GroupsProviderServiceClientFactory;
import tech.beshu.ror.commons.domain.LoggedUser;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.utils.MatcherWithWildcards;
import tech.beshu.ror.settings.rules.GroupsProviderAuthorizationRuleSettings;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class GroupsProviderAuthorizationAsyncRule extends AsyncAuthorization {

  private final GroupsProviderAuthorizationRuleSettings settings;
  private final GroupsProviderServiceClient client;
  private final MatcherWithWildcards usersMatcher;

  public GroupsProviderAuthorizationAsyncRule(GroupsProviderAuthorizationRuleSettings settings,
      GroupsProviderServiceClientFactory factory,
      ESContext context) {
    super(context);
    this.settings = settings;
    this.client = factory.getClient(settings.getUserGroupsProviderSettings());
    this.usersMatcher = new MatcherWithWildcards(settings.getUsers());
  }

  @Override
  protected CompletableFuture<Boolean> authorize(LoggedUser user) {
    if (!usersMatcher.match(user.getId())) {
      return CompletableFuture.completedFuture(false);
    }
    return client
        .fetchGroupsFor(user)
        .thenApply(fetchedGroupsForUser -> {

          Sets.SetView<String> intersection = Sets.intersection(settings.getGroups(), Sets.newHashSet(fetchedGroupsForUser));
          if (intersection.isEmpty()) {
            return false;
          }
          System.out.println("user: " + user.getId() + " has groups: " + fetchedGroupsForUser + ", intersected: " + intersection);

          if (user.getCurrentGroup().isPresent()) {
            String currGroup = user.getCurrentGroup().get();
            if (!intersection.contains(currGroup)) {
              return false;
            }
          }
          else {
            System.out.println("setting current group: " + intersection.iterator().next());
            user.setCurrentGroup(intersection.iterator().next());
          }

          Set<String> matchingUserPatterns = new MatcherWithWildcards(
              settings.getUserGroupsProviderSettings()
                      .getUser2availGroups().keySet())
              .matchingMatchers(Sets.newHashSet(user.getId()));
          Set<String> availGroupsForUser = Sets.newHashSet();
          System.out.println("globally available groups for user " + user.getId() + ": " + availGroupsForUser);
          for (String up : matchingUserPatterns) {
            availGroupsForUser.addAll(settings.getUserGroupsProviderSettings().getUser2availGroups().get(up));
          }
          availGroupsForUser = Sets.intersection(availGroupsForUser, fetchedGroupsForUser);

          user.addAvailableGroups(availGroupsForUser);

          return true;
        });
  }

  @Override
  public String getKey() {
    return settings.getName();
  }

}
