package com.datastax.astra.shell.cmd.iam;

import com.datastax.astra.shell.ExitCode;
import com.datastax.astra.shell.cmd.BaseCmd;
import com.datastax.astra.shell.cmd.AbstractCmd;
import com.github.rvesse.airline.annotations.Arguments;
import com.github.rvesse.airline.annotations.Command;
import com.github.rvesse.airline.annotations.restrictions.Required;

/**
 * Display user.
 * 
 * @author Cedrick LUNVEN (@clunven)
 */
@Command(name = AbstractCmd.GET, description = "Show user details")
public class UserGetCmd extends BaseCmd {
    
    /** identifier or email. */
    @Required
    @Arguments(title = "EMAIL", description = "User Email")
    public String user;
    
    /** {@inheritDoc} */
    public ExitCode execute() {
        return OperationIam.showUser(user);
    }

}