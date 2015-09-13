/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.cql3.statements;

import org.apache.cassandra.auth.*;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.cql3.RoleName;
import org.apache.cassandra.exceptions.*;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.transport.messages.ResultMessage;

public class CreateRoleStatement extends AuthenticationStatement
{
    private final RoleResource role;
    private final RoleOptions opts;
    private final boolean ifNotExists;

    public CreateRoleStatement(RoleName name, RoleOptions options, boolean ifNotExists)
    {
        this.role = RoleResource.role(name.getName());
        this.opts = options;
        this.ifNotExists = ifNotExists;
    }

    public void checkAccess(ClientState state) throws UnauthorizedException
    {
        super.checkPermission(state, Permission.CREATE, RoleResource.root()); //检查当前用户是否有root角色资源的创建权限
        if (opts.getSuperuser().isPresent())
        {
            if (opts.getSuperuser().get() && !state.getUser().isSuper()) //只有超级用户才有create superuser的权限
                throw new UnauthorizedException("Only superusers can create a role with superuser status");
        }
    }

    public void validate(ClientState state) throws RequestValidationException
    {
        opts.validate();

        if (role.getRoleName().isEmpty())
            throw new InvalidRequestException("Role name can't be an empty string");

        // validate login here before checkAccess to avoid leaking role existence to anonymous users.
        state.ensureNotAnonymous();

        if (!ifNotExists && DatabaseDescriptor.getRoleManager().isExistingRole(role))
            throw new InvalidRequestException(String.format("%s already exists", role.getRoleName()));
    }

    //之前的版本需要往system_auth.credentials表和system_auth.users表中分别增加一条新记录
    //如果其中之一失败了就不一致了，
    //并且两个表都不是本地的，
    //所以应该是一个分布式事务问题，当时并没有解决这个分布式事务问题
    //这里是新版本，都使用同一个system_auth.roles表了，所以不存在之前的问题
    public ResultMessage execute(ClientState state) throws RequestExecutionException, RequestValidationException
    {
        // not rejected in validate()
        if (ifNotExists && DatabaseDescriptor.getRoleManager().isExistingRole(role))
            return null;

        DatabaseDescriptor.getRoleManager().createRole(state.getUser(), role, opts);
        grantPermissionsToCreator(state);
        return null;
    }

    /**
     * Grant all applicable permissions on the newly created role to the user performing the request
     * see also: SchemaAlteringStatement#grantPermissionsToCreator and the overridden implementations
     * of it in subclasses CreateKeyspaceStatement & CreateTableStatement.
     * @param state
     */
    private void grantPermissionsToCreator(ClientState state)
    {
        // The creator of a Role automatically gets ALTER/DROP/AUTHORIZE permissions on it if:
        // * the user is not anonymous
        // * the configured IAuthorizer supports granting of permissions (not all do, AllowAllAuthorizer doesn't and
        //   custom external implementations may not)
        if (!state.getUser().isAnonymous())
        {
            try
            {
                DatabaseDescriptor.getAuthorizer().grant(AuthenticatedUser.SYSTEM_USER,
                                                         role.applicablePermissions(),
                                                         role,
                                                         RoleResource.role(state.getUser().getName()));
            }
            catch (UnsupportedOperationException e)
            {
                // not a problem, grant is an optional method on IAuthorizer
            }
        }
    }
}
