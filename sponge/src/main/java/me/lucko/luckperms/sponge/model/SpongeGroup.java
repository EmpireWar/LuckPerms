/*
 * Copyright (c) 2016 Lucko (Luck) <luck@lucko.me>
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.sponge.model;

import co.aikar.timings.Timing;
import com.google.common.collect.ImmutableList;
import lombok.Getter;
import me.lucko.luckperms.api.LocalizedNode;
import me.lucko.luckperms.api.MetaUtils;
import me.lucko.luckperms.api.Node;
import me.lucko.luckperms.api.context.ContextSet;
import me.lucko.luckperms.common.core.model.Group;
import me.lucko.luckperms.common.utils.ExtractedContexts;
import me.lucko.luckperms.sponge.LPSpongePlugin;
import me.lucko.luckperms.sponge.service.LuckPermsService;
import me.lucko.luckperms.sponge.service.LuckPermsSubject;
import me.lucko.luckperms.sponge.service.LuckPermsSubjectData;
import me.lucko.luckperms.sponge.timings.LPTiming;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.service.permission.NodeTree;
import org.spongepowered.api.service.permission.Subject;
import org.spongepowered.api.service.permission.SubjectCollection;
import org.spongepowered.api.util.Tristate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class SpongeGroup extends Group {

    @Getter
    private final GroupSubject spongeData;

    public SpongeGroup(String name, LPSpongePlugin plugin) {
        super(name, plugin);
        this.spongeData = new GroupSubject(plugin, this);
    }

    public static class GroupSubject extends LuckPermsSubject {
        private final SpongeGroup parent;
        private final LPSpongePlugin plugin;

        @Getter
        private final LuckPermsSubjectData subjectData;

        @Getter
        private final LuckPermsSubjectData transientSubjectData;

        private GroupSubject(LPSpongePlugin plugin, SpongeGroup parent) {
            this.parent = parent;
            this.plugin = plugin;
            this.subjectData = new LuckPermsSubjectData(true, plugin.getService(), parent);
            this.transientSubjectData = new LuckPermsSubjectData(false, plugin.getService(), parent);
        }

        @Override
        public String getIdentifier() {
            return parent.getObjectName();
        }

        @Override
        public Optional<CommandSource> getCommandSource() {
            return Optional.empty();
        }

        @Override
        public SubjectCollection getContainingCollection() {
            return plugin.getService().getGroupSubjects();
        }

        @Override
        public Tristate getPermissionValue(ContextSet contexts, String permission) {
            try (Timing ignored = plugin.getTimings().time(LPTiming.GROUP_GET_PERMISSION_VALUE)) {
                Map<String, Boolean> permissions = parent.getAllNodesFiltered(ExtractedContexts.generate(plugin.getService().calculateContexts(contexts))).stream()
                        .map(LocalizedNode::getNode)
                        .collect(Collectors.toMap(Node::getPermission, Node::getValue));

                Tristate t = NodeTree.of(permissions).get(permission);
                if (t != Tristate.UNDEFINED) {
                    return t;
                }

                t = plugin.getService().getGroupSubjects().getDefaults().getPermissionValue(LuckPermsService.convertContexts(contexts), permission);
                if (t != Tristate.UNDEFINED) {
                    return t;
                }

                t = plugin.getService().getDefaults().getPermissionValue(LuckPermsService.convertContexts(contexts), permission);
                return t;
            }
        }

        @Override
        public boolean isChildOf(ContextSet contexts, Subject parent) {
            try (Timing ignored = plugin.getTimings().time(LPTiming.GROUP_IS_CHILD_OF)) {
                return parent instanceof SpongeGroup && getPermissionValue(contexts, "group." + parent.getIdentifier()).asBoolean();
            }
        }

        @Override
        public List<Subject> getParents(ContextSet contexts) {
            try (Timing ignored = plugin.getTimings().time(LPTiming.GROUP_GET_PARENTS)) {
                List<Subject> subjects = parent.getAllNodesFiltered(ExtractedContexts.generate(plugin.getService().calculateContexts(contexts))).stream()
                        .map(LocalizedNode::getNode)
                        .filter(Node::isGroupNode)
                        .map(Node::getGroupName)
                        .map(s -> plugin.getService().getGroupSubjects().get(s))
                        .collect(Collectors.toList());

                subjects.addAll(plugin.getService().getGroupSubjects().getDefaults().getParents(LuckPermsService.convertContexts(contexts)));
                subjects.addAll(plugin.getService().getDefaults().getParents(LuckPermsService.convertContexts(contexts)));

                return ImmutableList.copyOf(subjects);
            }
        }

        @Override
        public Optional<String> getOption(ContextSet contexts, String s) {
            try (Timing ignored = plugin.getService().getPlugin().getTimings().time(LPTiming.GROUP_GET_OPTION)) {
                Optional<String> option;
                if (s.equalsIgnoreCase("prefix")) {
                    option = getChatMeta(contexts, true);

                } else if (s.equalsIgnoreCase("suffix")) {
                    option = getChatMeta(contexts, false);

                } else {
                    option = getMeta(contexts, s);
                }

                if (option.isPresent()) {
                    return option;
                }

                option = plugin.getService().getGroupSubjects().getDefaults().getOption(LuckPermsService.convertContexts(contexts), s);
                if (option.isPresent()) {
                    return option;
                }

                return plugin.getService().getDefaults().getOption(LuckPermsService.convertContexts(contexts), s);
            }
        }

        @Override
        public ContextSet getActiveContextSet() {
            try (Timing ignored = plugin.getTimings().time(LPTiming.GROUP_GET_ACTIVE_CONTEXTS)) {
                return plugin.getContextManager().getApplicableContext(this);
            }
        }

        private Optional<String> getChatMeta(ContextSet contexts, boolean prefix) {
            int priority = Integer.MIN_VALUE;
            String meta = null;

            for (Node n : parent.getAllNodesFiltered(ExtractedContexts.generate(plugin.getService().calculateContexts(contexts)))) {
                if (!n.getValue()) {
                    continue;
                }

                if (prefix ? !n.isPrefix() : !n.isSuffix()) {
                    continue;
                }

                Map.Entry<Integer, String> value = prefix ? n.getPrefix() : n.getSuffix();
                if (value.getKey() > priority) {
                    meta = value.getValue();
                    priority = value.getKey();
                }
            }

            return meta == null ? Optional.empty() : Optional.of(MetaUtils.unescapeCharacters(meta));
        }

        private Optional<String> getMeta(ContextSet contexts, String key) {
            for (Node n : parent.getAllNodesFiltered(ExtractedContexts.generate(plugin.getService().calculateContexts(contexts)))) {
                if (!n.getValue()) {
                    continue;
                }

                if (!n.isMeta()) {
                    continue;
                }

                Map.Entry<String, String> m = n.getMeta();
                if (!m.getKey().equalsIgnoreCase(key)) {
                    continue;
                }

                return Optional.of(MetaUtils.unescapeCharacters(m.getValue()));
            }

            return Optional.empty();
        }
    }
}
