# TODO

## Documentation

The documentation has been [exported to Markdown format](https://github.com/Spenhouet/confluence-markdown-exporter) from Confluence into a directory tree reflecting the page hierarchy with each page and its attachments located in a separate directory. There are however a number of issues as of now:

- There is no documentation build
- The links between documents and many page-internal links are broken
- The formatting of certain documentation constructs is suboptimal

In addition to the Markdown, exports have been done in Confluence *raw* and *view* format to use them as reference when fixing the documentation. For the same reason, currently all files use Confluence page IDs in their name.

Since currently none of these formats is suitable for reading, as a temporary workaround the pages were *additionally* exported in Confluence source HTML using the page title as file name. These files will be removed together with the Confluence *raw* and *view* files as soon as the documentation is in the desired state.

The core reference documentation is up to date, however the introductory documents need to be reviewed. The understandability of the reference documentation could probably be improved by providing examples for *all* instructions. (As it is now, there are examples only for *some* of them, which is inconsistent.)

The *Prefabricated Queries* page should use a better way to represent the query links than what was the result of the Confluence export. After the export, more queries were added to the Markdown file, so the HTML and XHTML files are now outdated (refer to the Markdown file to see *all* queries).

The *Rules Encyclopedia* should describe some of the rules in more detail.

## Java Code

Apart from the long term goal of factoring out the IIQ dependencies from the base framework as pluggable components (see README), there are currently some open topics and known problems:

* Application defined lookups will be refactored to use java functions instead of maps. The old method is deprecated, don't use it.
* The OrionQL select() instruction is inconsistent with CerberusLogic rule and action headers by taking only one selector. This should be changed.
* The entry set of a large HashMap can contain TreeNodes. OrionQL currently cannot handle them.
* OrionQL's ^*ClassName* parent navigation fails if the parent object is represented by a Hibernate proxy.
* The Generic Query is prepared to permit CSV download, however this is not yet implemented.
* The OrionQL reference contains specifications and ideas for new instructions that are still waiting for implementation.

## Macros and prefabricated queries

Considerable effort has been spent to refactor the queries that have grown over the years, moving reusable parts to the macro libraries as well as removing workarounds that are no more necessary. However, until now only a basic set has been reviewed and included into the *Prefabricated queries* page, and many of the queries that *are* there can be improved, as well as the macro libraries. Some queries could profit from new insights or new DSL features, or they should use macros that already exist. Also, the potential given by proxy rules to make queries linkable from other queries has only been scratched the surface (for such an example see *Tasks success timeline*). Most queries lack a description, and there are currently no keywords to support searching.
