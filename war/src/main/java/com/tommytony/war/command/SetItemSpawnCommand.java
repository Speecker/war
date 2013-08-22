package com.tommytony.war.command;

import java.util.logging.Level;

import org.bukkit.command.CommandSender;

import com.tommytony.war.War;

public class SetItemSpawnCommand extends AbstractOptionalZoneMakerCommand {

	public SetItemSpawnCommand(WarCommandHandler handler, CommandSender sender, String[] args) throws NotZoneMakerException {
	  super(handler, sender, args, true);
	}
	public void info(String message) {
		War.war.log("\033[1;32m"+message+"\033[0m", Level.INFO);
		this.msg(message);
	}
	@Override
	public boolean handle() {
		info("Itemspawn set to this location");
		return true;
	}

}
