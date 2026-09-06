package ru.arthaix.meshtiles.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;

/** /meshtiles list | undo [id] | redo [id] | cancel [player] | status */
public class CommandMeshTiles extends CommandBase {

    @Override
    public String getName() {
        return "meshtiles";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/meshtiles list | undo [id] | redo [id] | cancel [player] | status";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0; // per-action checks below
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return true;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) throw new CommandException(getUsage(sender));
        ImportJobManager m = ImportJobManager.INSTANCE;
        String sub = args[0].toLowerCase();
        WorldServer world = sender.getEntityWorld() instanceof WorldServer ? (WorldServer) sender.getEntityWorld() : server.worlds[0];
        switch (sub) {
            case "list":
                m.list(sender, world);
                break;
            case "status":
                if (m.activeJobs().isEmpty()) ImportJobManager.msg(sender, TextFormatting.GRAY + "No import is running.");
                for (ImportJob job : m.activeJobs().values())
                    ImportJobManager.msg(sender, "#" + job.historyId + " " + job.playerName + ": " + job.state + " " + job.percent() + "% (" + job.placed + "/" + job.totalBlocks + " blocks, queued " + job.queued() + ")");
                break;
            case "undo": {
                EntityPlayerMP player = getCommandSenderAsPlayer(sender);
                if (!ImportJobManager.isAllowed(player)) throw new CommandException("You need creative mode or op to undo imports.");
                int id = args.length > 1 ? parseInt(args[1], 1) : 0;
                m.undo(player, id);
                break;
            }
            case "redo": {
                EntityPlayerMP player = getCommandSenderAsPlayer(sender);
                if (!ImportJobManager.isAllowed(player)) throw new CommandException("You need creative mode or op to redo imports.");
                int id = args.length > 1 ? parseInt(args[1], 1) : 0;
                m.redo(player, id);
                break;
            }
            case "cancel": {
                EntityPlayerMP target;
                if (args.length > 1) {
                    if (!sender.canUseCommand(2, "meshtiles")) throw new CommandException("Op level 2 is needed to cancel other players' imports.");
                    target = getPlayer(server, sender, args[1]);
                } else {
                    target = getCommandSenderAsPlayer(sender);
                }
                m.cancel(target.getUniqueID(), sender);
                break;
            }
            default:
                throw new CommandException(getUsage(sender));
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, "list", "undo", "redo", "cancel", "status");
        if (args.length == 2 && args[0].equalsIgnoreCase("cancel")) return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
        if (args.length == 2 && args[0].equalsIgnoreCase("undo") && sender.getEntityWorld() instanceof WorldServer) {
            List<String> ids = new ArrayList<>();
            for (ImportHistory.Entry e : ImportHistory.get((WorldServer) sender.getEntityWorld()).entries()) ids.add(e.id + "");
            return getListOfStringsMatchingLastWord(args, ids);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("redo") && sender.getEntityWorld() instanceof WorldServer) {
            List<String> ids = new ArrayList<>();
            for (ImportHistory.Undone u : ImportHistory.get((WorldServer) sender.getEntityWorld()).undone()) ids.add(u.id + "");
            return getListOfStringsMatchingLastWord(args, ids);
        }
        return Arrays.asList();
    }
}
