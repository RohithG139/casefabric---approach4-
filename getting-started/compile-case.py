"""Compiles a CaseFabric Case Designer model into the single definitions XML that the case engine deploys - what the
Designer's Deploy button does in the browser. Usage:

    python compile-case.py <definitions-dir> <case-name> [-o <output.xml>]

e.g. python compile-case.py src/definitions development -o target/definitions/development.xml
(without -o the XML is written to stdout). deploy-case.py uses it to deploy through the engine's REST API.

Reads <case-name>.case and what it refers to: the .type (case file), the .caseteam (case roles), the .humantask files
(task forms), the .process files (e.g. mail) and <case-name>.dimensions (diagram). Supported plan items: stage,
humanTask, processTask, milestone, timerEvent, userEvent, planningTable; case tasks are not supported.

What compiling does:
- Every element in the plan becomes a planItem (a discretionaryItem in a planningTable) that refers to a separate
  definition; all definitions sit at casePlanModel level. The planItem keeps the documentation, the entry and exit
  criteria, the itemControl and (discretionary items) the authorizedRoleRefs.
- Every entry/exit criterion becomes a criterion with a sentryRef plus a sentry, in the stage that holds the item.
- A human task gets its .humantask implementation (inputs, outputs, task-model) merged with the case's mappings.
- A process task keeps its inputs and mappings; the .process file it refers to becomes a <process> definition next
  to the case (id = the file name), as the Designer's Deploy does.
- The .type becomes caseFileItemDefinitions and the caseFileItem tree, the .caseteam becomes caseRoles."""
import argparse
import os
import sys
import xml.etree.ElementTree as ET

CMMN = 'http://www.omg.org/spec/CMMN/20151109/MODEL'
CAF = 'org.cafienne'
ET.register_namespace('', CMMN)
ET.register_namespace('cafienne', CAF)
PROPERTY_TYPE = 'http://www.omg.org/spec/CMMN/PropertyType/'
UNSPECIFIED = 'http://www.omg.org/spec/CMMN/DefinitionType/Unspecified'
FORMAT_TYPE = {'date': 'date', 'time': 'time', 'date-time': 'dateTime'}  # JSON-schema format -> CMMN property type
JSON_TYPE = {'number': 'float'}  # JSON-schema types the Designer writes in .type files -> CMMN type, as its Deploy does
PLAN_ITEM_KINDS = ('stage', 'humanTask', 'processTask', 'milestone', 'timerEvent', 'userEvent')
# children that belong to the planItem, not to its definition
PLAN_ITEM_CHILDREN = ('documentation', 'entryCriterion', 'exitCriterion', 'itemControl')


def q(tag, ns=CMMN):
    return '{%s}%s' % (ns, tag)


def local(element):
    return element.tag.split('}')[-1]


def child(parent, tag, **attrib):
    return ET.SubElement(parent, q(tag), {k: v for k, v in attrib.items() if v is not None})


class Compiler:
    def __init__(self, folder, case_name):
        self.folder = folder
        self.case_name = case_name
        self.definitions = []  # plan item definitions, in order, all at casePlanModel level
        self.processes = []  # .process files referred to by process tasks, in order

    def read(self, file_name):
        path = os.path.join(self.folder, file_name)
        if not os.path.exists(path):
            raise SystemExit('Missing file referenced by the model: %s' % path)
        root = ET.parse(path).getroot()
        for element in root.iter():  # Designer files may omit the CMMN namespace (e.g. older .type files)
            if isinstance(element.tag, str) and not element.tag.startswith('{'):
                element.tag = q(element.tag)
        return root

    # ------------------------------------------------------------ plan
    def definition_id(self, item_id):
        return item_id[3:] if item_id.startswith('pi_') else item_id + '_def'

    def compile_item(self, source, container, stage, discretionary=False):
        """Adds the planItem for source to container (a stage definition or its planningTable), its sentries to stage
        and its definition to self.definitions."""
        kind = local(source)
        if kind not in PLAN_ITEM_KINDS:
            raise SystemExit('Unsupported plan element <%s id="%s">' % (kind, source.get('id')))
        item_id = source.get('id')
        item = child(container, 'discretionaryItem' if discretionary else 'planItem', id=item_id,
                     name=source.get('name'), definitionRef=self.definition_id(item_id))
        if discretionary and source.get('authorizedRoleRefs'):
            item.set('authorizedRoleRefs', source.get('authorizedRoleRefs'))
        for tag in ('documentation', 'entryCriterion', 'exitCriterion', 'itemControl'):
            for part in source.findall(q(tag)):
                if tag.endswith('Criterion'):
                    self.compile_criterion(part, item, stage)
                else:
                    item.append(part)

        definition = ET.Element(source.tag, {k: v for k, v in source.attrib.items()
                                             if not (discretionary and k == 'authorizedRoleRefs')})
        definition.set('id', self.definition_id(item_id))
        self.definitions.append(definition)  # before its children: keeps the order of the source
        if kind == 'stage':
            self.compile_stage_content(source, definition)
        else:
            for part in source:
                if local(part) in PLAN_ITEM_CHILDREN:
                    continue
                if kind == 'humanTask' and local(part) == 'extensionElements':
                    part = self.merge_human_task(part)
                definition.append(part)
            if kind == 'processTask' and source.get('processRef') not in self.processes:
                self.processes.append(source.get('processRef'))

    def compile_stage_content(self, source, stage):
        """planItems, then the sentries, then the planningTable - the order of the CMMN schema."""
        items, sentries = ET.Element('items'), ET.Element('sentries')
        for part in source:
            if local(part) in PLAN_ITEM_KINDS:
                self.compile_item(part, items, sentries)
        table = source.find(q('planningTable'))
        compiled_table = None
        if table is not None:
            compiled_table = ET.Element(q('planningTable'), dict(table.attrib))
            for part in table:
                if local(part) in PLAN_ITEM_KINDS:
                    self.compile_item(part, compiled_table, sentries, discretionary=True)
        for part in list(items) + list(sentries) + ([compiled_table] if compiled_table is not None else []):
            stage.append(part)

    def compile_criterion(self, criterion, item, stage):
        """<entryCriterion id=..> with an inline sentry becomes a criterion with sentryRef plus a sentry in stage."""
        sentry_id = 's_' + criterion.get('id')
        child(item, local(criterion), id=criterion.get('id'), sentryRef=sentry_id)
        sentry = child(stage, 'sentry', id=sentry_id)
        for part in criterion:
            sentry.append(part)

    def merge_human_task(self, extension):
        """The case's <cafienne:implementation humanTaskRef=..> (mappings only) plus the .humantask implementation."""
        implementation = extension.find(q('implementation', CAF))
        if implementation is None or not implementation.get('humanTaskRef'):
            return extension
        ref = implementation.get('humanTaskRef')
        task_impl = self.read(ref).find(q('implementation', CAF))
        if task_impl is None:
            raise SystemExit('%s has no cafienne:implementation' % ref)
        merged = ET.Element(q('implementation', CAF), dict(task_impl.attrib))
        merged.set('humanTaskRef', ref)
        for part in list(task_impl) + list(implementation):
            merged.append(part)
        compiled = ET.Element(extension.tag, dict(extension.attrib))
        compiled.append(merged)
        return compiled

    # ------------------------------------------------------------ case file
    def compile_type(self, type_ref):
        """.type properties of type object become caseFileItems (with a definition each); other properties become
        <property> elements of the item's definition. Returns (definitions, caseFileItems)."""
        type_root = self.read(type_ref)
        type_name = type_ref.rsplit('.', 1)[0]
        definitions = []

        def is_object(prop):  # an inline object, or a reference to another .type file
            return prop.get('type') == 'object' or prop.get('type', '').endswith('.type')

        def schema_of(prop):
            if prop.get('type', '').endswith('.type'):
                return self.read(prop.get('type')).find(q('schema'))
            return prop.find(q('schema'))

        def primitive_kind(sub):
            kind = FORMAT_TYPE.get(sub.get('format'), sub.get('type', 'string'))
            return JSON_TYPE.get(kind, kind)

        def items(schema, path, parent):
            for prop in schema.findall(q('property')):
                if not is_object(prop):
                    continue
                name = prop.get('name')
                item_path = path + [name]
                definition = ET.Element(q('caseFileItemDefinition'), {
                    'name': name, 'definitionType': UNSPECIFIED,
                    'id': '%s_type_%s.object' % (type_name, '_'.join(item_path))})
                nested = schema_of(prop)
                # A primitive sub-property repeats (multiplicity other than ZeroOrOne): CMMN only repeats case file
                # items, not plain properties, so it gets its own bare (property-less) item instead of a <property>.
                repeating = []
                for sub in (nested.findall(q('property')) if nested is not None else []):
                    if is_object(sub):
                        continue
                    if sub.get('multiplicity', 'ZeroOrOne') == 'ZeroOrOne':
                        ET.SubElement(definition, q('property'),
                                      {'name': sub.get('name'), 'type': PROPERTY_TYPE + primitive_kind(sub)})
                    else:
                        repeating.append(sub)
                definitions.append(definition)
                item = child(parent, 'caseFileItem', id='/'.join(item_path), name=name,
                             multiplicity=prop.get('multiplicity', 'ZeroOrOne'), definitionRef=definition.get('id'))
                has_object_children = nested is not None and any(is_object(p) for p in nested.findall(q('property')))
                if has_object_children or repeating:
                    children = child(item, 'children')
                    if has_object_children:
                        items(nested, item_path, children)
                    for sub in repeating:
                        sub_name = sub.get('name')
                        sub_path = item_path + [sub_name]
                        sub_definition = ET.Element(q('caseFileItemDefinition'), {
                            'name': sub_name, 'definitionType': UNSPECIFIED,
                            'id': '%s_type_%s.value' % (type_name, '_'.join(sub_path))})
                        definitions.append(sub_definition)
                        child(children, 'caseFileItem', id='/'.join(sub_path), name=sub_name,
                              multiplicity=sub.get('multiplicity'), definitionRef=sub_definition.get('id'))

        model = ET.Element(q('caseFileModel'), {q('typeRef', CAF): type_ref})
        items(type_root.find(q('schema')), [], model)
        definitions.append(ET.Element(q('caseFileItemDefinition'), {
            'name': type_name, 'definitionType': UNSPECIFIED, 'id': type_ref}))
        return definitions, model

    # ------------------------------------------------------------ whole case
    def compile(self):
        source = self.read(self.case_name + '.case')
        unsupported = [e.tag.split('}')[-1] for e in source.iter() if e.tag.split('}')[-1] == 'caseTask']
        if unsupported:
            raise SystemExit('%s uses %s, which this compiler does not support; deploy it from the Case Designer'
                             % (self.case_name, ', '.join(sorted(set(unsupported)))))
        root = ET.Element(q('definitions'))
        case = ET.Element(q('case'), {k: v for k, v in source.attrib.items()})
        for part in source:
            tag = local(part)
            if tag == 'caseFileModel':
                definitions, model = self.compile_type(part.get("typeRef"))
                for d in definitions:
                    root.append(d)
                case.append(model)
            elif tag == 'casePlanModel':
                plan = ET.SubElement(case, part.tag, dict(part.attrib))
                self.compile_stage_content(part, plan)
                for tag in ('documentation', 'exitCriterion'):  # the case plan's own documentation and exits
                    for i, criterion in enumerate(part.findall(q(tag))):
                        if tag == 'documentation':
                            plan.insert(i, criterion)
                        else:
                            self.compile_criterion(criterion, plan, plan)
                for d in self.definitions:
                    plan.append(d)
            elif tag == 'caseRoles':
                team = self.read(part.get('caseTeamRef'))
                roles = ET.SubElement(case, q('caseRoles'), {q('caseTeamRef', CAF): part.get('caseTeamRef')})
                for role in team.findall(q('role')):
                    roles.append(role)
            else:
                case.append(part)
        root.append(case)
        for ref in self.processes:
            process = self.read(ref)
            process.set('id', ref)
            process.set('name', ref)
            root.append(process)
        dimensions = os.path.join(self.folder, self.case_name + '.dimensions')
        if os.path.exists(dimensions):
            root.append(self.read(self.case_name + '.dimensions'))
        ET.indent(root, '    ')
        return ET.tostring(root, encoding='unicode') + '\n'


def compile_case(folder, case_name):
    return Compiler(folder, case_name).compile()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Compile a Case Designer model into deployable definitions XML.')
    parser.add_argument('folder', help='folder with the .case, .type, .caseteam, .humantask and .dimensions files')
    parser.add_argument('case', help='case name, e.g. development')
    parser.add_argument('-o', '--output', help='output file (default: stdout)')
    args = parser.parse_args()
    xml = compile_case(args.folder, args.case)
    if args.output:
        with open(args.output, 'w', encoding='utf-8', newline='\n') as f:
            f.write(xml)
        print('compiled %s -> %s' % (args.case, args.output), file=sys.stderr)
    else:
        sys.stdout.write(xml)
